# 验证隐藏输入包装的环境恢复和不泄露；只使用假Key和假后端，不启动Java或访问任何外部服务。
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$fixtureRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'target'))
$fixtureDirectory = Join-Path $fixtureRoot ('trend-key-test-' + [Guid]::NewGuid().ToString('N'))
$names = @('POLYHOUSE_DEEPSEEK_API_KEY','POLYHOUSE_TREND_ENABLED','POLYHOUSE_TREND_AI_ENABLED','POLYHOUSE_TREND_JOBS_ENABLED','POLYHOUSE_TREND_SCHEDULE_ENABLED','POLYHOUSE_TREND_CACHE_ENABLED','POLYHOUSE_TREND_LIVE_CNY')
$unchangedFlags = @('POLYHOUSE_TREND_JOBS_ENABLED','POLYHOUSE_TREND_SCHEDULE_ENABLED','POLYHOUSE_TREND_CACHE_ENABLED')
$original = @{}
foreach ($name in $names) { $original[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$outputs = [Collections.Generic.List[object]]::new()
try {
    New-Item -ItemType Directory -Path $fixtureDirectory -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot 'scripts/Invoke-TrendAiLocal.ps1') -Destination $fixtureDirectory
    @'
param($Action,[switch]$SkipWslRedis,[switch]$TrendLiveOnly)
if ($env:POLYHOUSE_DEEPSEEK_API_KEY -ne 'fixture-deepseek-key-1234') { throw '假Key注入失败' }
if ($Action -eq 'Run' -and ($env:POLYHOUSE_TREND_ENABLED -ne 'true' -or $env:POLYHOUSE_TREND_AI_ENABLED -ne 'true')) { throw '原启动开关不匹配' }
if ($Action -eq 'Verify' -and (-not $TrendLiveOnly -or $env:POLYHOUSE_TREND_LIVE_CNY -cne 'true')) { throw '真实验收路由未显式限定' }
Write-Output ('FixtureJobs:' + $env:POLYHOUSE_TREND_JOBS_ENABLED)
Write-Output ('FixtureSchedule:' + $env:POLYHOUSE_TREND_SCHEDULE_ENABLED)
Write-Output ('FixtureCache:' + $env:POLYHOUSE_TREND_CACHE_ENABLED)
throw 'Expected fake backend failure'
'@ | Set-Content -LiteralPath (Join-Path $fixtureDirectory 'Invoke-LocalAuth.ps1') -Encoding utf8
    foreach ($name in $names) { if (Test-Path -LiteralPath "Env:$name") { Remove-Item -LiteralPath "Env:$name" } }
    function Read-Host { param($Prompt,[switch]$AsSecureString) ConvertTo-SecureString 'fixture-deepseek-key-1234' -AsPlainText -Force }
    foreach ($jobState in @('false', 'true')) {
        $outputs.Clear()
        foreach ($flag in $unchangedFlags) { [Environment]::SetEnvironmentVariable($flag, $jobState, 'Process') }
        $inspection = & (Join-Path $fixtureDirectory 'Invoke-TrendAiLocal.ps1') -Action Inspect
        if ($inspection.JobsEnabled -ne ($jobState -eq 'true') -or $inspection.ScheduleEnabled -ne ($jobState -eq 'true') -or
                $inspection.CacheEnabled -ne ($jobState -eq 'true') -or $inspection.KeyConfigured) { throw 'Inspect state mismatch' }
        try {
            & (Join-Path $fixtureDirectory 'Invoke-TrendAiLocal.ps1') -Action Run -EnableAi 6>&1 | ForEach-Object { $outputs.Add($_) }
            throw 'Expected failure did not occur'
        } catch { if ($_.Exception.Message -ne 'Expected fake backend failure') { throw } }
        foreach ($name in $names | Where-Object { $_ -notin $unchangedFlags }) {
            if (Test-Path -LiteralPath "Env:$name") { throw 'Process environment was not restored' }
        }
        foreach ($flag in $unchangedFlags) {
            if ([Environment]::GetEnvironmentVariable($flag, 'Process') -ne $jobState) { throw 'Execution flag was changed' }
        }
        foreach ($kind in @('Jobs','Schedule','Cache')) { if (-not $outputs.Contains('Fixture' + $kind + ':' + $jobState)) { throw 'Backend flag mismatch' } }
        if (($outputs | Out-String).Contains('fixture-deepseek-key-1234')) { throw 'Key leaked in captured output' }
    }
    $outputs.Clear()
    try {
        & (Join-Path $fixtureDirectory 'Invoke-TrendAiLocal.ps1') -Action VerifyLive -CurrencyCny 6>&1 | ForEach-Object { $outputs.Add($_) }
        throw '预期假后端失败未出现'
    } catch { if ($_.Exception.Message -ne 'Expected fake backend failure') { throw } }
    foreach ($name in $names | Where-Object { $_ -notin $unchangedFlags }) {
        if (Test-Path -LiteralPath "Env:$name") { throw '真实验收包装未恢复环境' }
    }
    if (($outputs | Out-String).Contains('fixture-deepseek-key-1234')) { throw '真实验收包装回显了假Key' }
    try {
        & (Join-Path $fixtureDirectory 'Invoke-TrendAiLocal.ps1') -Action VerifyLive
        throw '未核对币种也进入了真实验收'
    } catch { if ($_.Exception.Message -notlike '*人民币账户*') { throw } }
    '安全输入包装：任务/定时/缓存开关均保持原值，假后端失败后环境恢复，输出未含Key、未写凭据文件'
} finally {
    foreach ($name in $names) {
        if ($null -eq $original[$name]) { if (Test-Path -LiteralPath "Env:$name") { Remove-Item -LiteralPath "Env:$name" } }
        else { [Environment]::SetEnvironmentVariable($name, $original[$name], 'Process') }
    }
    $resolved = [IO.Path]::GetFullPath($fixtureDirectory)
    if ($resolved.StartsWith($fixtureRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path $resolved -Leaf) -match '^trend-key-test-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    } else { throw 'Unsafe fixture cleanup path' }
    $original.Clear()
}
