<#
.SYNOPSIS
只读核对或首次授予唯一屋主；默认不修改任何账号。
.DESCRIPTION
仅供本机开发使用，复用 Git 忽略的 IDEA 数据库/Redis 预设，不提供 HTTP 入口。
编号和用户名用于防误操作；授权来自操作系统、文件和数据库权限，并非这两个公开字段。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('\A[1-9][0-9]{0,18}\z')]
    [string]$UserId,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('\A[a-z0-9_]{4,32}\z')]
    [string]$Username,
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
# PowerShell ValidatePattern 默认忽略大小写，必须额外使用区分大小写检查，不能悄悄改正目标。
$ownerNumericId = 0L
if (-not [long]::TryParse($UserId, [ref]$ownerNumericId) -or $ownerNumericId -le 0 -or
    $Username -cnotmatch '\A[a-z0-9_]{4,32}\z') { throw '请按个人中心原样提供正整数编号及小写账号。' }
$ownerProject = Split-Path -Parent $PSScriptRoot
$ownerWorkspace = Join-Path $ownerProject '.idea/workspace.xml'
& git -C $ownerProject check-ignore --quiet -- '.idea/workspace.xml'
if ($LASTEXITCODE -ne 0 -or (& git -C $ownerProject ls-files -- '.idea/workspace.xml')) {
    throw '个人预设未安全排除在 Git 之外，拒绝读取。'
}
$ownerDocument = [Xml.XmlDocument]::new()
$ownerDocument.XmlResolver = $null
try { $ownerDocument.Load($ownerWorkspace) } catch { throw '无法读取 IDEA 个人预设；不要在聊天提供凭据。' }
$ownerRuns = $ownerDocument.SelectNodes('//component[@name="RunManager"]/configuration[@name="PolyhouseApplication"]')
if ($ownerRuns.Count -ne 1) { throw '缺少唯一的 PolyhouseApplication 运行配置。' }
$ownerAllowed = @('DB_USERNAME', 'DB_PASSWORD', 'DB_URL', 'REDIS_HOST', 'REDIS_PORT', 'REDIS_DATABASE',
    'REDIS_USERNAME', 'REDIS_PASSWORD', 'REDIS_CONNECT_TIMEOUT', 'REDIS_TIMEOUT')
$ownerPreset = @{}
foreach ($ownerName in $ownerAllowed) {
    $ownerNodes = $ownerRuns[0].SelectNodes("./envs/env[@name='$ownerName']")
    if ($ownerNodes.Count -gt 1) { throw '个人配置存在重复变量，拒绝猜测。' }
    if ($ownerNodes.Count -eq 1) { $ownerPreset[$ownerName] = $ownerNodes[0].GetAttribute('value') }
}
if ([string]::IsNullOrWhiteSpace($ownerPreset['DB_USERNAME']) -or [string]::IsNullOrWhiteSpace($ownerPreset['DB_PASSWORD'])) {
    throw '个人预设缺少数据库连接信息，请使用现有 Initialize-LocalAuth.ps1 配置。'
}
$ownerPrevious = @{}
Push-Location -LiteralPath $ownerProject
try {
    # 允许列表导入并恢复；环境里原有的 DB_URL/Redis 主机也必须检查，不能误连远端。
    foreach ($ownerName in $ownerPreset.Keys) {
        $ownerPrevious[$ownerName] = [Environment]::GetEnvironmentVariable($ownerName, 'Process')
        [Environment]::SetEnvironmentVariable($ownerName, $ownerPreset[$ownerName], 'Process')
    }
    $ownerUrl = [Environment]::GetEnvironmentVariable('DB_URL', 'Process')
    if ($ownerUrl -and $ownerUrl -notmatch '^jdbc:mysql://(localhost|127\.0\.0\.1)(:\d+)?/polyhouse_db(?:\?|$)') {
        throw '本机脚本仅允许 localhost/127.0.0.1 的 polyhouse_db；生产部署请按运维文档单独配置。'
    }
    $ownerRedisHost = [Environment]::GetEnvironmentVariable('REDIS_HOST', 'Process')
    if ($ownerRedisHost -and $ownerRedisHost -notin @('localhost', '127.0.0.1', '::1')) {
        throw '本机脚本仅允许本机 Redis。'
    }
    # 用户输入已限制为数字及 ASCII 账号，作为单个 Maven 参数交付，不使用 Invoke-Expression 或字符串 SQL。
    $ownerArguments = "--user-id=$UserId --username=$Username"
    if ($Apply) { $ownerArguments += ' --apply' }
    & mvn -q spring-boot:run '-Dspring-boot.run.main-class=com.polyethene.polyhouse.ops.OwnerBootstrapCommand' `
        "-Dspring-boot.run.arguments=$ownerArguments"
    if ($LASTEXITCODE -ne 0) { throw '屋主命令未完成；核对安全提示后先重新只读检查，不要直接改表重试。' }
} finally {
    foreach ($ownerName in $ownerPrevious.Keys) {
        if ($null -eq $ownerPrevious[$ownerName]) {
            if (Test-Path -LiteralPath "Env:$ownerName") { Remove-Item -LiteralPath "Env:$ownerName" }
        } else { [Environment]::SetEnvironmentVariable($ownerName, $ownerPrevious[$ownerName], 'Process') }
    }
    $ownerPreset.Clear()
    $ownerPrevious.Clear()
    Pop-Location
}
