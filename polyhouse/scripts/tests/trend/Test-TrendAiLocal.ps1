# 验证隐藏输入包装的环境恢复和不泄露；只使用假Key和假后端，不启动Java或访问任何外部服务。
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$fixtureRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'target'))
$fixtureDirectory = Join-Path $fixtureRoot ('trend-key-test-' + [Guid]::NewGuid().ToString('N'))
$names = @('POLYHOUSE_DEEPSEEK_API_KEY','POLYHOUSE_TREND_ENABLED','POLYHOUSE_TREND_AI_ENABLED','POLYHOUSE_TREND_JOBS_ENABLED')
$original = @{}
foreach ($name in $names) { $original[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$outputs = [Collections.Generic.List[object]]::new()
try {
    New-Item -ItemType Directory -Path $fixtureDirectory -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot 'scripts/Invoke-TrendAiLocal.ps1') -Destination $fixtureDirectory
    @'
param($Action,[switch]$SkipWslRedis)
if ($env:POLYHOUSE_DEEPSEEK_API_KEY -ne 'fixture-deepseek-key-1234' -or $env:POLYHOUSE_TREND_ENABLED -ne 'true' -or $env:POLYHOUSE_TREND_AI_ENABLED -ne 'true') { throw 'Test injection failed' }
Write-Output ('FixtureJobs:' + $env:POLYHOUSE_TREND_JOBS_ENABLED)
throw 'Expected fake backend failure'
'@ | Set-Content -LiteralPath (Join-Path $fixtureDirectory 'Invoke-LocalAuth.ps1') -Encoding utf8
    foreach ($name in $names) { if (Test-Path -LiteralPath "Env:$name") { Remove-Item -LiteralPath "Env:$name" } }
    function Read-Host { param($Prompt,[switch]$AsSecureString) ConvertTo-SecureString 'fixture-deepseek-key-1234' -AsPlainText -Force }
    foreach ($jobState in @('false', 'true')) {
        $outputs.Clear()
        [Environment]::SetEnvironmentVariable('POLYHOUSE_TREND_JOBS_ENABLED', $jobState, 'Process')
        $inspection = & (Join-Path $fixtureDirectory 'Invoke-TrendAiLocal.ps1') -Action Inspect
        if ($inspection.JobsEnabled -ne ($jobState -eq 'true') -or $inspection.KeyConfigured) { throw 'Inspect state mismatch' }
        try {
            & (Join-Path $fixtureDirectory 'Invoke-TrendAiLocal.ps1') -Action Run -EnableAi 6>&1 | ForEach-Object { $outputs.Add($_) }
            throw 'Expected failure did not occur'
        } catch { if ($_.Exception.Message -ne 'Expected fake backend failure') { throw } }
        foreach ($name in $names | Where-Object { $_ -ne 'POLYHOUSE_TREND_JOBS_ENABLED' }) {
            if (Test-Path -LiteralPath "Env:$name") { throw 'Process environment was not restored' }
        }
        if ([Environment]::GetEnvironmentVariable('POLYHOUSE_TREND_JOBS_ENABLED', 'Process') -ne $jobState -or
                -not $outputs.Contains('FixtureJobs:' + $jobState)) { throw 'Jobs flag was changed' }
        if (($outputs | Out-String).Contains('fixture-deepseek-key-1234')) { throw 'Key leaked in captured output' }
    }
    '安全输入包装：两种任务开关均保持原值，假后端失败后环境恢复，输出未含Key、未写凭据文件'
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
