<#
.SYNOPSIS
可选的安全输入包装：隐藏输入 DeepSeek Key，仅注入本次启动进程树，不持久保存。
.DESCRIPTION
Inspect 只检查当前进程配置存在性，不调用 DeepSeek、不验证密钥有效性。
Run 复用原 Invoke-LocalAuth 启动；EnableAi 仅在已确认迁移后显式启用模块，不自动迁移。
VerifyLive -CurrencyCny 仅用于已核对人民币账户的G4真实小样本：专属持久库、月预算一角、至多两项，不迁移开发库。
不要把 Key 写进命令参数、聊天、截图或文件。相同 Windows 权限的程序仍可能读取运行时内存。
#>
[CmdletBinding()]
param(
    [ValidateSet('Inspect', 'Run', 'VerifyLive')][string]$Action = 'Inspect',
    [switch]$EnableAi,
    [switch]$CurrencyCny,
    [switch]$SkipWslRedis
)
$ErrorActionPreference = 'Stop'
if ($Action -eq 'VerifyLive' -and -not $CurrencyCny) { throw '真实验收前须核对人民币账户，并显式指定 -CurrencyCny。' }
if ($CurrencyCny -and $Action -ne 'VerifyLive') { throw '-CurrencyCny仅用于VerifyLive。' }
if ($Action -eq 'Inspect') {
    [pscustomobject]@{
        KeyConfigured = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('POLYHOUSE_DEEPSEEK_API_KEY', 'Process'))
        ModuleEnabled = [Environment]::GetEnvironmentVariable('POLYHOUSE_TREND_ENABLED', 'Process') -eq 'true'
        AiEnabled = [Environment]::GetEnvironmentVariable('POLYHOUSE_TREND_AI_ENABLED', 'Process') -eq 'true'
        JobsEnabled = [Environment]::GetEnvironmentVariable('POLYHOUSE_TREND_JOBS_ENABLED', 'Process') -eq 'true'
        ScheduleEnabled = [Environment]::GetEnvironmentVariable('POLYHOUSE_TREND_SCHEDULE_ENABLED', 'Process') -eq 'true'
        CacheEnabled = [Environment]::GetEnvironmentVariable('POLYHOUSE_TREND_CACHE_ENABLED', 'Process') -eq 'true'
    }
    return
}
$names = @('POLYHOUSE_DEEPSEEK_API_KEY', 'POLYHOUSE_TREND_ENABLED', 'POLYHOUSE_TREND_AI_ENABLED', 'POLYHOUSE_TREND_LIVE_CNY')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$secureKey = $null
$keyPointer = [IntPtr]::Zero
try {
    $keyText = $previous['POLYHOUSE_DEEPSEEK_API_KEY']
    if ([string]::IsNullOrWhiteSpace($keyText)) {
        $secureKey = Read-Host '输入聚妙屋专用 DeepSeek Key（隐藏输入，不写文件）' -AsSecureString
        $keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
        $keyText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)
    }
    if ($keyText -notmatch '^[A-Za-z0-9_-]{16,256}$') { throw 'Key格式不符合预期，请重新输入；不要将内容发到聊天。' }
    [Environment]::SetEnvironmentVariable('POLYHOUSE_DEEPSEEK_API_KEY', $keyText, 'Process')
    if ($EnableAi) {
        [Environment]::SetEnvironmentVariable('POLYHOUSE_TREND_ENABLED', 'true', 'Process')
        [Environment]::SetEnvironmentVariable('POLYHOUSE_TREND_AI_ENABLED', 'true', 'Process')
    }
    if ($Action -eq 'VerifyLive') {
        [Environment]::SetEnvironmentVariable('POLYHOUSE_TREND_LIVE_CNY', 'true', 'Process')
        Write-Host 'G4真实验收：仅本机专属库，一角月预算持久记账，定时关闭；同月重复运行不换号重发。Key不写文件。'
        & (Join-Path $PSScriptRoot 'Invoke-LocalAuth.ps1') -Action Verify -TrendLiveOnly
    } else {
        Write-Host 'Key已注入本次启动进程树，未验证有效性、不做迁移；任务及定时开关保持原值，启用时可能继续已受理任务或自动受理新任务并计费。'
        & (Join-Path $PSScriptRoot 'Invoke-LocalAuth.ps1') -Action Run -SkipWslRedis:$SkipWslRedis
    }
} finally {
    foreach ($name in $names) {
        if ($null -eq $previous[$name]) {
            if (Test-Path -LiteralPath "Env:$name") { Remove-Item -LiteralPath "Env:$name" }
        } else { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    }
    if ($keyPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer) }
    if ($null -ne $secureKey) { $secureKey.Dispose() }
    $keyText = $null
    $previous.Clear()
}
