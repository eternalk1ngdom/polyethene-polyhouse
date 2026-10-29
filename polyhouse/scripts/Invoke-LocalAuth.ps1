<#
.SYNOPSIS
复用 IDEA 个人预设检查、验收或启动本机后端，后续会话无需再次索取凭据。
.DESCRIPTION
默认 Verify：编译并启用 MySQL、Redis、认证事务和真实运行密钥的全部测试。
Verify -AccountManagementOnly：只在随机隔离库执行 P3 管理验收。
Verify -AccountProvisioningOnly：只在随机隔离库执行开户验收，包含本测试专属屋主。
Verify -AccountSecurityOnly：只在随机隔离库执行账号安全验收，不运行历史 OWNER 夹具。
Inspect 只返回配置状态，Run 默认保持 Ubuntu WSL 并检查无认证 Redis 就绪后启动 Spring Boot；
SkipWslRedis 供其他 Redis 环境自行管理依赖。所有进程环境在退出时恢复。
#>
[CmdletBinding()]
param(
    [ValidateSet('Inspect', 'Verify', 'Run')]
    [string]$Action = 'Verify',
    # 非 WSL 或已启用 Redis 认证的环境可显式选择自行管理依赖。
    [switch]$SkipWslRedis,
    # P1 使用随机隔离库，仅执行账号安全验收，不运行含 OWNER 夹具的历史全量测试。
    [switch]$AccountSecurityOnly,
    # P2 只运行隔离库开户验收，不在开发库创建测试屋主。
    [switch]$AccountProvisioningOnly,
    # P3 仅在隔离库验收管理功能，保护正式屋主。
    [switch]$AccountManagementOnly
)

$ErrorActionPreference = 'Stop'
if (([int][bool]$AccountSecurityOnly + [int][bool]$AccountProvisioningOnly + [int][bool]$AccountManagementOnly) -gt 1) { throw '请只选择一种隔离验收范围。' }
if (($AccountSecurityOnly -or $AccountProvisioningOnly -or $AccountManagementOnly) -and $Action -ne 'Verify') { throw '隔离验收选项仅用于 Verify。' }
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
    'REDIS_CONNECT_TIMEOUT', 'REDIS_TIMEOUT', 'POLYHOUSE_AVATAR_DIRECTORY')
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
$wslKeeper = $null
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
        if (-not $SkipWslRedis) {
            # 本机约定为 Ubuntu 无认证 Redis。持有独立 WSL 进程直到后端退出，
            # 避免把 systemd 的 enabled 状态误当成 Windows 侧持续可访问。
            $redisPort = if ($preset['REDIS_PORT']) { [int]$preset['REDIS_PORT'] } else { 6379 }
            $probeHost = if ($redisHost) { $redisHost } else { '127.0.0.1' }
            if ($preset['REDIS_PASSWORD'] -or $preset['REDIS_USERNAME']) {
                throw 'WSL 自动就绪检查仅适用于本机无认证 Redis；请使用 -SkipWslRedis 并自行验证连接。'
            }
            $wslCommand = Get-Command wsl.exe -ErrorAction Stop
            $wslKeeper = Start-Process -FilePath $wslCommand.Source -ArgumentList @('-d', 'Ubuntu', '--exec', 'sleep', 'infinity') -WindowStyle Hidden -PassThru
            $ready = $false
            $deadline = [DateTime]::UtcNow.AddSeconds(20)
            while ([DateTime]::UtcNow -lt $deadline -and -not $wslKeeper.HasExited) {
                $client = [System.Net.Sockets.TcpClient]::new()
                try {
                    $connection = $client.ConnectAsync($probeHost, $redisPort)
                    if ($connection.Wait(1000)) {
                        $stream = $client.GetStream()
                        $stream.ReadTimeout = 1000
                        $stream.WriteTimeout = 1000
                        $ping = [System.Text.Encoding]::ASCII.GetBytes("PING`r`n")
                        $stream.Write($ping, 0, $ping.Length)
                        # RESP 简单字符串必须完整匹配，单纯端口开放不能证明 Redis 就绪。
                        $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII)
                        $ready = $reader.ReadLine() -ceq '+PONG'
                    }
                } catch {
                    # 启动和端口转发需要时间，限时重试；不输出底层连接异常或凭据。
                    $ready = $false
                } finally {
                    $client.Dispose()
                }
                if ($ready) { break }
                Start-Sleep -Milliseconds 200
            }
            if (-not $ready) { throw '本机 Redis 未就绪，后端尚未启动。请检查 Ubuntu 的 redis-server 和 Windows 本机端口转发。' }
            Write-Host '本机 Redis 已就绪；后端运行期间保持 Ubuntu WSL 活跃。'
        }
        & mvn spring-boot:run
        if ($LASTEXITCODE -ne 0) { throw '本地后端启动失败，请查看不含凭据的应用诊断。' }
    } else {
        & mvn -q compile -DskipTests
        if ($LASTEXITCODE -ne 0) { throw '后端编译失败。' }
        if ($AccountManagementOnly) {
            & mvn -q '-Dtest=AccountManagementIntegrationTest' '-Dpolyhouse.management.integration=true' test
        } elseif ($AccountSecurityOnly) {
            & mvn -q '-Dtest=AccountSecurityIntegrationTest' '-Dpolyhouse.security.integration=true' test
        } elseif ($AccountProvisioningOnly) {
            & mvn -q '-Dtest=AccountProvisioningIntegrationTest' '-Dpolyhouse.provisioning.integration=true' test
        } else {
            & mvn -q '-Dpolyhouse.mysql.integration=true' '-Dpolyhouse.redis.integration=true' `
                '-Dpolyhouse.auth.integration=true' '-Dpolyhouse.jwt.runtime=true' test
        }
        if ($LASTEXITCODE -ne 0) {
            if ($AccountManagementOnly) { throw '账号管理隔离验收失败，请检查 Surefire 汇总。' }
            if ($AccountProvisioningOnly) { throw '账号开户隔离验收失败，请检查 Surefire 汇总。' }
            if ($AccountSecurityOnly) { throw '账号安全隔离验收失败，请检查 Surefire 汇总。' }
            throw '本机认证全量验收失败，请检查 Surefire 汇总。'
        }
    }
} finally {
    # 只释放本次启动的辅助进程，不停止 Redis 服务、不关闭整个 WSL。
    if ($null -ne $wslKeeper -and -not $wslKeeper.HasExited) {
        Stop-Process -Id $wslKeeper.Id -ErrorAction Continue
    }
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
