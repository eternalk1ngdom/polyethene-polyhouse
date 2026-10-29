<#
.SYNOPSIS
供 IDEA 启动前任务检查并按需启动本机 Ubuntu WSL Redis。
.DESCRIPTION
仅适用于本项目本地约定：Ubuntu、systemd redis-server、127.0.0.1:6379、无认证。
不安装软件、不改服务配置、不清理数据。失败返回非零退出码，阻止 IDEA 继续启动 Java。
KeepAlive 是隐藏辅助进程内部入口；命名互斥量保证同一 Windows 会话只保留一个保活进程。
#>
[CmdletBinding()]
param(
    [switch]$KeepAlive
)

$ErrorActionPreference = 'Stop'

# 必须验证 Redis 协议响应，不能把“端口被别的软件占用”视为依赖就绪。
function Test-LocalRedisPong {
    $redisClient = [System.Net.Sockets.TcpClient]::new()
    try {
        if (-not $redisClient.ConnectAsync('127.0.0.1', 6379).Wait(500)) { return $false }
        $redisStream = $redisClient.GetStream()
        $redisStream.ReadTimeout = 500
        $redisStream.WriteTimeout = 500
        $pingBytes = [System.Text.Encoding]::ASCII.GetBytes("PING`r`n")
        $redisStream.Write($pingBytes, 0, $pingBytes.Length)
        # 限定响应长度，错误服务或不完整响应不能无限占用启动前任务。
        $replyBytes = [byte[]]::new(7)
        $replyOffset = 0
        while ($replyOffset -lt $replyBytes.Length) {
            $readCount = $redisStream.Read($replyBytes, $replyOffset, $replyBytes.Length - $replyOffset)
            if ($readCount -eq 0) { return $false }
            $replyOffset += $readCount
        }
        return [System.Text.Encoding]::ASCII.GetString($replyBytes) -ceq "+PONG`r`n"
    } catch {
        # 冷启动时连接拒绝属于预期重试，不回显底层网络信息。
        return $false
    } finally {
        $redisClient.Dispose()
    }
}

# PONG 可能来自别的终端临时唤起的 WSL，必须另行确认共享保活进程已持有互斥量。
function Test-LocalRedisKeeper {
    $probeMutex = $null
    try {
        $probeMutex = [System.Threading.Mutex]::OpenExisting('Local\Polyhouse.LocalRedis.Ubuntu.Keeper')
        if ($probeMutex.WaitOne(0)) {
            $probeMutex.ReleaseMutex()
            return $false
        }
        return $true
    } catch {
        # 辅助进程尚未建立互斥量时继续限时等待，不视为检查成功。
        return $false
    } finally {
        if ($null -ne $probeMutex) { $probeMutex.Dispose() }
    }
}

try {
    $wslExecutable = (Get-Command wsl.exe -ErrorAction Stop).Source
    if ($KeepAlive) {
        $createdKeeper = $false
        $keeperMutex = [System.Threading.Mutex]::new($true, 'Local\Polyhouse.LocalRedis.Ubuntu.Keeper', [ref]$createdKeeper)
        try {
            # 重复或并发点击运行会创建短命检查进程，只有互斥量所有者保持 WSL。
            if (-not $createdKeeper) { exit 0 }
            & $wslExecutable -d Ubuntu --exec sleep infinity
            if ($LASTEXITCODE -ne 0) { throw 'Ubuntu keep-alive process exited unsuccessfully.' }
        } finally {
            if ($createdKeeper) { $keeperMutex.ReleaseMutex() }
            $keeperMutex.Dispose()
        }
        exit 0
    }

    $powerShellExecutable = (Get-Command powershell.exe -ErrorAction Stop).Source
    # 即使 Redis 已在线也需保活；systemd 服务本身不保证 WSL 在最后一个终端关闭后持续运行。
    $keeperArguments = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-File', ('"{0}"' -f $PSCommandPath), '-KeepAlive')
    $keeperProcess = Start-Process -FilePath $powerShellExecutable -ArgumentList $keeperArguments `
        -WindowStyle Hidden -PassThru
    $keeperProcess.Dispose()

    if (-not (Test-LocalRedisPong)) {
        # 仅启动既有服务，不重启在线 Redis、不修改监听/认证/持久化配置。
        # WSL 的 root 只用于这一条固定 systemctl 命令，不作为 Java 运行身份。
        $serviceStartInfo = [System.Diagnostics.ProcessStartInfo]::new()
        $serviceStartInfo.FileName = $wslExecutable
        $serviceStartInfo.Arguments = '-d Ubuntu -u root --exec systemctl start redis-server'
        $serviceStartInfo.UseShellExecute = $false
        $serviceStartInfo.CreateNoWindow = $true
        $serviceStartInfo.RedirectStandardOutput = $true
        $serviceStartInfo.RedirectStandardError = $true
        $serviceProcess = [System.Diagnostics.Process]::Start($serviceStartInfo)
        try {
            # 异步排空输出，避免 WSL 提示填满管道；失败只给固定排查提示。
            $serviceOutput = $serviceProcess.StandardOutput.ReadToEndAsync()
            $serviceError = $serviceProcess.StandardError.ReadToEndAsync()
            if (-not $serviceProcess.WaitForExit(20000)) {
                $serviceProcess.Kill()
                throw 'Timed out starting Ubuntu Redis. Check WSL and redis-server manually.'
            }
            if ($serviceProcess.ExitCode -ne 0) {
                throw 'Unable to start the existing redis-server service in Ubuntu.'
            }
        } finally {
            $serviceProcess.Dispose()
        }
    }

    $redisReady = $false
    $readinessDeadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $redisReady = (Test-LocalRedisPong) -and (Test-LocalRedisKeeper)
        if ($redisReady) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $readinessDeadline)
    if (-not $redisReady) {
        throw 'Local Redis/keep-alive is not ready. Check WSL forwarding, Redis authentication/configuration and script execution permissions.'
    }
    Write-Host 'Local Redis is ready (PONG). Ubuntu keep-alive is active; Java may start.'
    exit 0
} catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
    exit 1
}
