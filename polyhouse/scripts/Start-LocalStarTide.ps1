<#
.SYNOPSIS
一条命令启动本机星潮和聚妙屋前端，复用原认证预设及已迁移的开发库。
.DESCRIPTION
默认使用现有 Ubuntu Redis 启动检查。WSL 故障时可显式选择本机临时 Docker Redis。
只开启模块和手动更新，AI、内容缓存和每日定时均关闭。启动前应完成 010～014 增量迁移。
#>
[CmdletBinding()]
param([switch]$DockerRedis)

$ErrorActionPreference = 'Stop'
$frontendRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../polyhouse-web')).Path
$vite = Join-Path $frontendRoot 'node_modules/vite/bin/vite.js'
if (-not (Test-Path -LiteralPath $vite -PathType Leaf)) { throw '前端依赖缺失，请先按 README 准备 polyhouse-web。' }

function Test-LocalPort([int]$Port, [switch]$RedisPong) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        if (-not $client.ConnectAsync('127.0.0.1', $Port).Wait(550)) { return $false }
        if (-not $RedisPong) { return $true }
        $stream = $client.GetStream()
        $stream.ReadTimeout = 600
        $stream.WriteTimeout = 600
        $ping = [Text.Encoding]::ASCII.GetBytes("PING`r`n")
        $stream.Write($ping, 0, $ping.Length)
        $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::ASCII)
        return $reader.ReadLine() -ceq '+PONG'
    } catch { return $false }
    finally { $client.Dispose() }
}

if (Test-LocalPort 8080) { throw '本机 8080 已有服务，请先停止上一轮启动的 Java 后端。' }
$reuseFront = Test-LocalPort 5173
if ($reuseFront) {
    try { $existingFront = Invoke-WebRequest 'http://127.0.0.1:5173/polyethene-polyhouse/' -UseBasicParsing -TimeoutSec 3 }
    catch { throw '本机 5173 已有服务，无法确认它是聚妙屋前端。' }
    if ($existingFront.Content -notmatch '/polyethene-polyhouse/src/main\.ts' -or $existingFront.Content -notmatch '@vite/client') {
        throw '本机 5173 已被其他服务占用，请先核对已有进程。'
    }
}
$settings = @{
    PORT = '8080'; SERVER_ADDRESS = '127.0.0.1'; REDIS_HOST = '127.0.0.1'; REDIS_PORT = '6379'
    POLYHOUSE_TREND_ENABLED = 'true'; POLYHOUSE_TREND_JOBS_ENABLED = 'true'
    POLYHOUSE_TREND_AI_ENABLED = 'false'; POLYHOUSE_TREND_SCHEDULE_ENABLED = 'false'
    POLYHOUSE_TREND_CACHE_ENABLED = 'false'; POLYHOUSE_DEEPSEEK_API_KEY = $null
}
$previous = @{}
$container = $null
$frontend = $null
$dockerEndpoint = $null

try {
    if ($DockerRedis) {
        if (-not (Test-LocalPort 6379 -RedisPong)) {
            if (Test-LocalPort 6379) { throw '6379 已被其他程序或带认证 Redis 占用，请先核对现有服务。' }
            $dockerEndpoint = (& docker context inspect --format '{{.Endpoints.docker.Host}}' | Out-String).Trim()
            if ($LASTEXITCODE -ne 0 -or $dockerEndpoint -notmatch '^npipe:/{4}\./pipe/[\w-]+$') {
                throw '请确认 Docker Desktop 当前连接本机引擎。'
            }
            $name = 'polyhouse-local-startide-redis'
            & docker --host $dockerEndpoint run --detach --rm --pull=never --name $name --publish '127.0.0.1:6379:6379' --memory 96m --tmpfs /data redis:7-alpine --appendonly no | Out-Null
            if ($LASTEXITCODE -ne 0) { throw '临时 Redis 启动失败，请检查 Docker Desktop 和本机已有镜像。' }
            $container = $name
            $ready = $false
            for ($attempt = 0; $attempt -lt 16; $attempt++) {
                if (Test-LocalPort 6379 -RedisPong) { $ready = $true; break }
                Start-Sleep -Milliseconds 250
            }
            if (-not $ready) { throw '临时 Redis 未返回 PONG。' }
        }
        Write-Host '本机 Redis 已就绪。'
    } else {
        & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'Ensure-LocalRedis.ps1')
        if ($LASTEXITCODE -ne 0) { throw 'WSL Redis 检查未通过。可在 Docker Desktop 可用时加 -DockerRedis。' }
    }

    if ($reuseFront) { Write-Host '复用正在运行的聚妙屋前端。' }
    else {
        $viteProcess = [Diagnostics.ProcessStartInfo]::new()
        $viteProcess.FileName = (Get-Command node -CommandType Application | Select-Object -First 1).Source
        $viteProcess.WorkingDirectory = $frontendRoot
        $viteProcess.Arguments = ('"{0}" --host 127.0.0.1 --port 5173 --strictPort' -f $vite)
        $viteProcess.UseShellExecute = $false
        $viteProcess.CreateNoWindow = $true
        $viteProcess.EnvironmentVariables['VITE_API_BASE_URL'] = '/api'
        # 前端子进程只需要 API 路径，避免继承在外部终端设置的后端私有连接信息。
        foreach ($key in @($viteProcess.EnvironmentVariables.Keys)) {
            if ($key -match '^(DB_|SPRING_DATASOURCE_|REDIS_|POLYHOUSE_(JWT|DEEPSEEK|GITHUB))') {
                $viteProcess.EnvironmentVariables.Remove($key)
            }
        }
        $frontend = [Diagnostics.Process]::Start($viteProcess)
        $frontendReady = $false
        for ($attempt = 0; $attempt -lt 24; $attempt++) {
            if ($frontend.HasExited) { break }
            if (Test-LocalPort 5173) { $frontendReady = $true; break }
            Start-Sleep -Milliseconds 250
        }
        if (-not $frontendReady) { throw '前端未在 5173 启动，请查看启动输出。' }
    }

    foreach ($key in $settings.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process')
    }
    Write-Host '看到 Tomcat started 后，打开 http://127.0.0.1:5173/polyethene-polyhouse/#/trend 。'
    & (Join-Path $PSScriptRoot 'Invoke-LocalAuth.ps1') -Action Run -SkipWslRedis
} finally {
    if ($null -ne $frontend) {
        if (-not $frontend.HasExited) { $frontend.Kill(); $frontend.WaitForExit(3000) | Out-Null }
        $frontend.Dispose()
    }
    if ($null -ne $container) {
        # --rm 只回收本次成功创建的容器，已有的 Redis 从不在此停止。
        & docker --host $dockerEndpoint stop $container | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "本次容器未能自动停止：$container" }
    }
    foreach ($key in $previous.Keys) {
        if ($null -eq $previous[$key]) {
            if (Test-Path -LiteralPath "Env:$key") { Remove-Item -LiteralPath "Env:$key" }
        } else { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    }
}
