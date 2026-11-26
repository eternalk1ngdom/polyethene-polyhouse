<#
.SYNOPSIS
前后端静默验收入口：通过时仅输出单行摘要，失败时完整展开原始日志。
.DESCRIPTION
用于减少日常开发与 AI 协作时的终端输出噪音，不改变测试/构建逻辑。
默认关闭全部真实 MySQL/Redis、浏览器与外部采集开关，绝不触碰本机正式屋主数据。
#>
[CmdletBinding()]
param(
    [ValidateSet('All', 'Context', 'Backend', 'Frontend', 'BackendCompile', 'BackendTest', 'FrontendLint', 'FrontendTest', 'FrontendBuild')]
    [string]$Scope = 'All',

    # 可选定向过滤：后端对应 Maven -Dtest=...；前端测试对应传给 run-tests-quiet.mjs 的文件或通配路径。
    [string]$Filter = '',

    # 需要临时查看通过阶段完整输出时显式开启。
    [switch]$VerboseOutput
)

$ErrorActionPreference = 'Stop'
$verifyBackend = Split-Path -Parent $PSScriptRoot
$verifyRoot = Split-Path -Parent $verifyBackend
$verifyFrontend = Join-Path $verifyRoot 'polyhouse-web'
$surefireDir = Join-Path $verifyBackend 'target\surefire-reports'

# 记录执行前 Surefire XML 文件修改时间，避免把历史残留报告算进本轮后端测试计数。
function Get-SurefireSnapshot {
    $snapshot = @{}
    if (Test-Path -LiteralPath $surefireDir -PathType Container) {
        foreach ($f in (Get-ChildItem -LiteralPath $surefireDir -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue)) {
            $snapshot[$f.Name] = $f.LastWriteTimeUtc.Ticks
        }
    }
    return $snapshot
}

# 汇总本轮新生成或更新的 Surefire XML 用例统计，即使 Maven 使用 -q 也能报告准确用例数。
function Get-SurefireDeltaSummary {
    param([hashtable]$BeforeSnapshot)
    if (-not (Test-Path -LiteralPath $surefireDir -PathType Container)) {
        return ''
    }
    $suiteCount = 0
    $totalTests = 0
    $totalFailures = 0
    $totalErrors = 0
    $totalSkipped = 0
    foreach ($f in (Get-ChildItem -LiteralPath $surefireDir -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue)) {
        $prevTicks = if ($BeforeSnapshot.ContainsKey($f.Name)) { $BeforeSnapshot[$f.Name] } else { -1L }
        if ($f.LastWriteTimeUtc.Ticks -le $prevTicks) {
            continue
        }
        try {
            [xml]$xml = Get-Content -Raw -Encoding UTF8 -LiteralPath $f.FullName
            $ts = $xml.testsuite
            if ($null -ne $ts) {
                $suiteCount++
                $totalTests += [int]$ts.tests
                $totalFailures += [int]$ts.failures
                $totalErrors += [int]$ts.errors
                $totalSkipped += [int]$ts.skipped
            }
        } catch {
            # 单个报告解析异常不影响主流程，忽略后回退无计数摘要。
        }
    }
    if ($suiteCount -eq 0) {
        return ''
    }
    $passed = $totalTests - $totalFailures - $totalErrors - $totalSkipped
    return " | $suiteCount 类, $passed 通过 / $totalFailures 失败 / $totalErrors 错误 / $totalSkipped 跳过"
}

# 统一执行单个验收步骤：使用原生调用兼容 .exe/.cmd/.ps1，捕获全部输出，成功仅报一行，失败展开全文并终止。
function Invoke-QuietStep {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$WorkingDir,
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$PassThroughSingleLine,
        [switch]$CollectSurefireSummary
    )

    $beforeSurefire = if ($CollectSurefireSummary) { Get-SurefireSnapshot } else { $null }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Push-Location -LiteralPath $WorkingDir
    $prevPreference = $ErrorActionPreference
    try {
        # 临时放宽为 Continue，避免子进程正常的 stderr 警告被 PowerShell 误判为终止异常；真实成败以 LASTEXITCODE 为准。
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = 0
        $rawLines = & $Command @Arguments 2>&1
        $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
    } finally {
        $ErrorActionPreference = $prevPreference
        Pop-Location
    }

    $sw.Stop()
    $elapsed = ('{0:F2}s' -f $sw.Elapsed.TotalSeconds)
    $combined = ($rawLines | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine

    if ($exitCode -eq 0) {
        if ($VerboseOutput) {
            Write-Host "[PASS] $Label ($elapsed)"
            if ($combined) { Write-Host $combined.TrimEnd() }
        } elseif ($PassThroughSingleLine -and $combined) {
            Write-Host $combined.Trim()
        } else {
            $summaryHint = if ($CollectSurefireSummary) { Get-SurefireDeltaSummary -BeforeSnapshot $beforeSurefire } else { '' }
            Write-Host "[PASS] $Label ($elapsed$summaryHint)"
        }
        return
    }

    Write-Host "[FAIL] $Label (退出码 $exitCode, $elapsed)" -ForegroundColor Red
    if ($combined) { Write-Host $combined.TrimEnd() }
    exit $exitCode
}

$needBackend = $Scope -in @('All', 'Backend', 'BackendCompile', 'BackendTest')
$needFrontend = $Scope -in @('All', 'Frontend', 'FrontendLint', 'FrontendTest', 'FrontendBuild')

if ($needBackend) {
    foreach ($tool in @('mvn', 'java')) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw "缺少 $tool，请按 README 准备 JDK 21 与 Maven 后再执行。"
        }
    }
}
if ($needFrontend) {
    foreach ($tool in @('node', 'npm')) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw "缺少 $tool，请按 README 准备 Node 环境后再执行。"
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $verifyFrontend 'node_modules') -PathType Container)) {
        throw '缺少前端依赖，请先在 polyhouse-web 中执行 npm ci；此脚本不自动安装依赖。'
    }
}

if ($Scope -in @('All', 'Backend', 'BackendCompile')) {
    Invoke-QuietStep -Label '后端编译' -WorkingDir $verifyBackend -Command 'mvn' -Arguments @('-q', 'compile', '-DskipTests')
}

if ($Scope -in @('All', 'Backend', 'BackendTest')) {
    $mvnArgs = @(
        '-q',
        '-DskipTests=false', '-Dmaven.test.skip=false',
        '-Dpolyhouse.mysql.integration=false', '-Dpolyhouse.redis.integration=false',
        '-Dpolyhouse.auth.integration=false', '-Dpolyhouse.jwt.runtime=false',
        '-Dpolyhouse.capacity.integration=false', '-Dpolyhouse.security.integration=false',
        '-Dpolyhouse.provisioning.integration=false', '-Dpolyhouse.management.integration=false',
        '-Dpolyhouse.profile.integration=false', '-Dpolyhouse.workspace.browser=false',
        '-Dpolyhouse.trend.integration=false', '-Dpolyhouse.trend.redis=false',
        '-Dpolyhouse.trend.http=false', '-Dpolyhouse.trend.browser=false',
        '-Dpolyhouse.trend.live=false', '-Dpolyhouse.trend.live.acceptance=false'
    )
    if ($Filter) {
        $mvnArgs += "-Dtest=$Filter"
    }
    $mvnArgs += 'test'
    $label = if ($Filter) { "后端定向测试 ($Filter)" } else { '后端普通测试' }
    Invoke-QuietStep -Label $label -WorkingDir $verifyBackend -Command 'mvn' -Arguments $mvnArgs -CollectSurefireSummary
}

if ($Scope -in @('All', 'Frontend', 'FrontendLint')) {
    Invoke-QuietStep -Label '前端静态检查' -WorkingDir $verifyFrontend -Command 'npm' -Arguments @('run', 'lint:check')
}

if ($Scope -in @('All', 'Frontend', 'FrontendTest')) {
    $nodeArgs = @('scripts/run-tests-quiet.mjs')
    if ($Filter) {
        $nodeArgs += $Filter
    }
    $label = if ($Filter) { "前端定向测试 ($Filter)" } else { '前端自动测试' }
    Invoke-QuietStep -Label $label -WorkingDir $verifyFrontend -Command 'node' -Arguments $nodeArgs -PassThroughSingleLine
}

if ($Scope -in @('All', 'Frontend', 'FrontendBuild')) {
    Invoke-QuietStep -Label '前端类型检查与生产构建' -WorkingDir $verifyFrontend -Command 'npm' -Arguments @('run', 'build')
}

if ($Scope -in @('All', 'Context')) {
    & (Join-Path $PSScriptRoot 'Check-ContextBudget.ps1') -Quiet
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

Write-Host "[OK] 静默验收完成（Scope=$Scope）；不代表真实数据库/Redis、浏览器或线上部署验收通过。"
