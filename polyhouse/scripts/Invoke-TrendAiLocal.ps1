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
    [switch]$EditorialSample,
    [switch]$ThinkingLow,
    [switch]$SkipWslRedis
)
$ErrorActionPreference = 'Stop'
if ($Action -eq 'VerifyLive' -and -not $CurrencyCny) { throw '真实验收前须核对人民币账户，并显式指定 -CurrencyCny。' }
if ($CurrencyCny -and $Action -ne 'VerifyLive') { throw '-CurrencyCny仅用于VerifyLive。' }
if ($EditorialSample -and $Action -ne 'VerifyLive') { throw '单篇写作验收仅用于 VerifyLive。' }
if ($Action -eq 'Inspect') {
    [pscustomobject]@{
        KeyConfigured = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('POLYHOUSE_DEEPSEEK_API_KEY', 'Process'))
        ModuleEnabled = [Environment]::GetEnvironmentVariable('POLYHOUSE_TREND_ENABLED', 'Process') -eq 'true'
        AiEnabled = [Environment]::GetEnvironmentVariable('POLYHOUSE_TREND_AI_ENABLED', 'Process') -eq 'true'
        ThinkingEnabled = [Environment]::GetEnvironmentVariable('POLYHOUSE_TREND_AI_THINKING_ENABLED', 'Process') -eq 'true'
        JobsEnabled = [Environment]::GetEnvironmentVariable('POLYHOUSE_TREND_JOBS_ENABLED', 'Process') -eq 'true'
        ScheduleEnabled = [Environment]::GetEnvironmentVariable('POLYHOUSE_TREND_SCHEDULE_ENABLED', 'Process') -eq 'true'
        CacheEnabled = [Environment]::GetEnvironmentVariable('POLYHOUSE_TREND_CACHE_ENABLED', 'Process') -eq 'true'
    }
    return
}
$names = @('POLYHOUSE_DEEPSEEK_API_KEY', 'POLYHOUSE_TREND_ENABLED', 'POLYHOUSE_TREND_AI_ENABLED',
    'POLYHOUSE_TREND_AI_THINKING_ENABLED', 'POLYHOUSE_TREND_LIVE_CNY', 'POLYHOUSE_TREND_JOBS_ENABLED', 'POLYHOUSE_TREND_EDITORIAL_SAMPLE')
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
    if ($ThinkingLow) {
        [Environment]::SetEnvironmentVariable('POLYHOUSE_TREND_AI_THINKING_ENABLED', 'true', 'Process')
    } elseif ($Action -eq 'VerifyLive') {
        [Environment]::SetEnvironmentVariable('POLYHOUSE_TREND_AI_THINKING_ENABLED', 'false', 'Process')
    }
    if ($Action -eq 'VerifyLive') {
        [Environment]::SetEnvironmentVariable('POLYHOUSE_TREND_LIVE_CNY', 'true', 'Process')
        [Environment]::SetEnvironmentVariable('POLYHOUSE_TREND_EDITORIAL_SAMPLE', $EditorialSample.IsPresent.ToString().ToLowerInvariant(), 'Process')
        if ($EditorialSample) {
            $modeLabel = if ($ThinkingLow) { '轻度思考（low）' } else { '关闭思考' }
            Write-Host ("新版写作验收（" + $modeLabel + "）：固定 cursor/plugins 一篇，共用原验收账本，不重试。")
        }
        Write-Host 'G4真实验收：仅本机专属库，一角月预算持久记账，定时关闭；同月重复运行不换号重发。Key不写文件。'
        & (Join-Path $PSScriptRoot 'Invoke-LocalAuth.ps1') -Action Verify -TrendLiveOnly
    } else {
        # 拿 Key 启动仍沿用原约定，必须显式打开任务才可以恢复可能产生费用的旧任务。
        if ([string]::IsNullOrWhiteSpace($previous['POLYHOUSE_TREND_JOBS_ENABLED'])) {
            [Environment]::SetEnvironmentVariable('POLYHOUSE_TREND_JOBS_ENABLED', 'false', 'Process')
        }
        Write-Host 'Key已注入本次启动进程树。此入口仅在显式开启任务时运行执行器，定时配置沿用原值。'
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
