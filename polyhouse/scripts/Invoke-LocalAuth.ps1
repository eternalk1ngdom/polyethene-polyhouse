<#
.SYNOPSIS
复用 IDEA 个人预设检查、验收或启动本机后端，后续会话无需再次索取凭据。
.DESCRIPTION
默认 Verify：编译并启用 MySQL、Redis、认证事务和真实运行密钥的全部测试。
Inspect 只返回配置状态，Run 使用同一预设启动 Spring Boot；所有进程环境在退出时恢复。
#>
[CmdletBinding()]
param(
    [ValidateSet('Inspect', 'Verify', 'Run')]
    [string]$Action = 'Verify'
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$workspacePath = Join-Path $projectDirectory '.idea/workspace.xml'
if (-not (Test-Path -LiteralPath $workspacePath -PathType Leaf)) {
    throw '缺少本机预设，请先执行 scripts/Initialize-LocalAuth.ps1；不要在聊天中提供凭据。'
}
& git -C $projectDirectory check-ignore --quiet -- '.idea/workspace.xml'
if ($LASTEXITCODE -ne 0 -or (& git -C $projectDirectory ls-files -- '.idea/workspace.xml')) {
    throw '个人预设没有被安全排除在 Git 之外，拒绝读取凭据。'
}
$workspace = [Xml.XmlDocument]::new()
$workspace.XmlResolver = $null
try {
    $workspace.Load($workspacePath)
} catch {
    throw '无法读取个人运行配置；不回显可能含凭据的 XML 错误。'
}
$runConfigurations = $workspace.SelectNodes('//component[@name="RunManager"]/configuration[@name="PolyhouseApplication"]')
if ($runConfigurations.Count -ne 1) {
    throw '找不到唯一的 PolyhouseApplication 个人运行配置。'
}

# 只导入本项目允许的连接及密钥变量，不能执行 XML 内容、导入 PATH 或把任意变量送入子进程。
$allowedNames = @('DB_USERNAME', 'DB_PASSWORD', 'DB_URL', 'POLYHOUSE_JWT_SECRET',
    'REDIS_HOST', 'REDIS_PORT', 'REDIS_DATABASE', 'REDIS_USERNAME', 'REDIS_PASSWORD',
    'REDIS_CONNECT_TIMEOUT', 'REDIS_TIMEOUT')
$preset = @{}
foreach ($name in $allowedNames) {
    $nodes = $runConfigurations[0].SelectNodes("./envs/env[@name='$name']")
    if ($nodes.Count -gt 1) { throw '个人预设中存在重复环境变量，拒绝猜测使用哪一项。' }
    if ($nodes.Count -eq 1) { $preset[$name] = $nodes[0].GetAttribute('value') }
}
$databaseConfigured = -not [string]::IsNullOrWhiteSpace($preset['DB_USERNAME']) -and
    -not [string]::IsNullOrWhiteSpace($preset['DB_PASSWORD'])
$keyValid = $false
try {
    if ($preset.ContainsKey('POLYHOUSE_JWT_SECRET')) {
        $secretBytes = [Convert]::FromBase64String($preset['POLYHOUSE_JWT_SECRET'])
        $keyValid = $preset['POLYHOUSE_JWT_SECRET'].Length -eq 44 -and $secretBytes.Length -eq 32
    }
} catch {
    # 检查模式只报告无效，不回显解码异常中的原始字符串。
    $keyValid = $false
} finally {
    if ($null -ne $secretBytes) { [Array]::Clear($secretBytes, 0, $secretBytes.Length) }
}
if ($Action -eq 'Inspect') {
    [pscustomobject]@{ Configuration='PolyhouseApplication'; DatabaseConfigured=$databaseConfigured; JwtFormatValid=$keyValid; GitIgnored=$true }
    return
}
if (-not $databaseConfigured -or -not $keyValid) {
    throw '本机预设缺少数据库凭据或有效运行密钥，请先完成 Initialize-LocalAuth 配置。'
}

$previousEnvironment = @{}
Push-Location -LiteralPath $projectDirectory
try {
    foreach ($name in $preset.Keys) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $preset[$name], 'Process')
    }
    # 该入口会执行带清理的实例测试，只允许项目本机开发库，避免误继承指向外部环境的连接变量。
    $databaseUrl = [Environment]::GetEnvironmentVariable('DB_URL', 'Process')
    if ($databaseUrl -and $databaseUrl -notmatch '^jdbc:mysql://(localhost|127\.0\.0\.1)(:\d+)?/polyhouse_db(?:\?|$)') {
        throw '本机验收入口只允许 localhost/127.0.0.1 的 polyhouse_db 数据库。'
    }
    $redisHost = [Environment]::GetEnvironmentVariable('REDIS_HOST', 'Process')
    if ($redisHost -and $redisHost -notin @('localhost', '127.0.0.1', '::1')) {
        throw '本机验收入口只允许本机 Redis。'
    }
    if ($Action -eq 'Run') {
        & mvn spring-boot:run
        if ($LASTEXITCODE -ne 0) { throw '本地后端启动失败，请查看不含凭据的应用诊断。' }
    } else {
        & mvn -q compile -DskipTests
        if ($LASTEXITCODE -ne 0) { throw '后端编译失败。' }
        & mvn -q '-Dpolyhouse.mysql.integration=true' '-Dpolyhouse.redis.integration=true' `
            '-Dpolyhouse.auth.integration=true' '-Dpolyhouse.jwt.runtime=true' test
        if ($LASTEXITCODE -ne 0) { throw '本机认证全量验收失败，请检查 Surefire 汇总。' }
    }
} finally {
    foreach ($name in $previousEnvironment.Keys) {
        if ($null -eq $previousEnvironment[$name]) {
            # PowerShell 到 .NET 的 null/string 转换可能留下空环境变量；原本不存在的键要真正移除。
            if (Test-Path -LiteralPath "Env:$name") { Remove-Item -LiteralPath "Env:$name" }
        } else {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
        }
    }
    $preset.Clear()
    $previousEnvironment.Clear()
    Pop-Location
}
