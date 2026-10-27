<#
.SYNOPSIS
一次配置本机 IDEA 认证预设，已有运行密钥保持不变。
.DESCRIPTION
凭据只写入已被 Git 忽略的 .idea/workspace.xml；不修改系统环境、不输出密码或密钥。
首次调用可用 SecureString 提供数据库密码，后续重复调用复用现有值，不自动轮换密钥。
#>
[CmdletBinding()]
param(
    [string]$DatabaseUsername,
    [Security.SecureString]$DatabasePassword,
    [string]$AvatarDirectory
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$workspacePath = Join-Path $projectDirectory '.idea/workspace.xml'

# 必须先确认文件不在版本库中，不能仅因 .idea 出现在忽略规则里就假定已有文件未被跟踪。
& git -C $projectDirectory check-ignore --quiet -- '.idea/workspace.xml'
if ($LASTEXITCODE -ne 0) {
    throw '个人运行配置未被 Git 忽略，拒绝保存凭据。'
}
$trackedWorkspace = & git -C $projectDirectory ls-files -- '.idea/workspace.xml'
if ($trackedWorkspace) {
    throw '个人运行配置已被 Git 跟踪，拒绝保存凭据。'
}
if (-not (Test-Path -LiteralPath $workspacePath -PathType Leaf)) {
    throw '找不到 IDEA 个人运行配置，请先在 IDEA 中建立 PolyhouseApplication 运行配置。'
}

$workspace = [Xml.XmlDocument]::new()
$workspace.PreserveWhitespace = $true
$workspace.XmlResolver = $null
try {
    $workspace.Load($workspacePath)
} catch {
    throw '无法读取 IDEA 个人运行配置；不回显可能含凭据的 XML 错误。'
}
$runConfigurations = $workspace.SelectNodes('//component[@name="RunManager"]/configuration[@name="PolyhouseApplication"]')
if ($runConfigurations.Count -ne 1) {
    throw '必须存在唯一的 PolyhouseApplication 个人运行配置，拒绝猜测保存目标。'
}
$runConfiguration = $runConfigurations[0]
$environment = $runConfiguration.SelectSingleNode('./envs')
if ($null -eq $environment) {
    $environment = $workspace.CreateElement('envs')
    [void]$runConfiguration.AppendChild($environment)
}

function Set-PrivateEnvironmentValue {
    param([string]$Name, [string]$Value)
    # Name 只由本脚本固定的变量名提供，不把外部输入拼进 XPath。
    $nodes = $environment.SelectNodes("./env[@name='$Name']")
    if ($nodes.Count -gt 1) {
        throw '个人运行配置存在重复环境变量，请先在 IDEA 中消除歧义。'
    }
    if ($nodes.Count -eq 0) {
        $node = $workspace.CreateElement('env')
        $node.SetAttribute('name', $Name)
        [void]$environment.AppendChild($node)
    } else {
        $node = $nodes[0]
    }
    $node.SetAttribute('value', $Value)
}

$passwordNode = $environment.SelectSingleNode('./env[@name="DB_PASSWORD"]')
if ($null -eq $DatabasePassword -and ($null -eq $passwordNode -or [string]::IsNullOrWhiteSpace($passwordNode.GetAttribute('value')))) {
    $DatabasePassword = Read-Host '首次配置数据库密码（输入不显示）' -AsSecureString
}
if ([string]::IsNullOrWhiteSpace($DatabaseUsername)) {
    $usernameNode = $environment.SelectSingleNode('./env[@name="DB_USERNAME"]')
    $DatabaseUsername = if ($null -ne $usernameNode) { $usernameNode.GetAttribute('value') } else { 'root' }
    if ([string]::IsNullOrWhiteSpace($DatabaseUsername)) { throw '数据库账号不能为空。' }
}
Set-PrivateEnvironmentValue -Name 'DB_USERNAME' -Value $DatabaseUsername
if ($null -ne $DatabasePassword) {
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($DatabasePassword)
    try {
        $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
        if ([string]::IsNullOrWhiteSpace($plainPassword)) {
            throw '数据库密码不能为空。'
        }
        Set-PrivateEnvironmentValue -Name 'DB_PASSWORD' -Value $plainPassword
    } finally {
        $plainPassword = $null
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
}

$secretNode = $environment.SelectSingleNode('./env[@name="POLYHOUSE_JWT_SECRET"]')
$keyCreated = $null -eq $secretNode -or [string]::IsNullOrWhiteSpace($secretNode.GetAttribute('value'))
if ($keyCreated) {
    $keyBytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($keyBytes)
        Set-PrivateEnvironmentValue -Name 'POLYHOUSE_JWT_SECRET' -Value ([Convert]::ToBase64String($keyBytes))
    } finally {
        $random.Dispose()
        [Array]::Clear($keyBytes, 0, $keyBytes.Length)
    }
} else {
    $existingSecret = $secretNode.GetAttribute('value')
    try {
        $existingKey = [Convert]::FromBase64String($existingSecret)
        if ($existingSecret.Length -ne 44 -or $existingKey.Length -ne 32) {
            throw 'invalid key'
        }
    } catch {
        # 已有无效密钥不能被初始化脚本静默换新，以免用户误以为旧会话仍有效。
        throw '已有运行密钥格式不合法；未覆盖，请先明确是否需要更换。'
    } finally {
        if ($null -ne $existingKey) { [Array]::Clear($existingKey, 0, $existingKey.Length) }
        $existingSecret = $null
    }
}

$avatarNode = $environment.SelectSingleNode('./env[@name="POLYHOUSE_AVATAR_DIRECTORY"]')
if ([string]::IsNullOrWhiteSpace($AvatarDirectory)) {
    # 默认选项目所在磁盘的独立目录，保留已有个人选择，不在 C 盘或源码下保存原图。
    $AvatarDirectory = if ($null -ne $avatarNode) { $avatarNode.GetAttribute('value') } else {
        Join-Path ([IO.Path]::GetPathRoot($projectDirectory)) 'polyhouse-data/avatars'
    }
}
if (-not [IO.Path]::IsPathFullyQualified($AvatarDirectory)) { throw '头像目录必须为绝对路径。' }
Set-PrivateEnvironmentValue -Name 'POLYHOUSE_AVATAR_DIRECTORY' -Value ([IO.Path]::GetFullPath($AvatarDirectory))

try {
    # 只生成个人配置的环境变量节点，保留原有运行参数及 XML 空白，不重建整个 IDEA 项目配置。
    $workspace.Save($workspacePath)
} catch {
    throw '无法保存 IDEA 个人运行配置；不回显可能含凭据的 XML 错误。'
}
[pscustomobject]@{
    Configuration = 'PolyhouseApplication'
    DatabaseConfigured = $true
    JwtConfigured = $true
    JwtCreated = $keyCreated
    GitIgnored = $true
}
