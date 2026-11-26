<#
.SYNOPSIS
前后端静默验收入口：通过时仅输出单行摘要，失败时完整展开原始日志。
.DESCRIPTION
用于减少日常开发与 AI 协作时的终端输出噪音，不改变测试/构建逻辑。
默认关闭全部真实 MySQL/Redis、浏览器与外部采集开关，绝不触碰本机正式屋主数据。
#>
[CmdletBinding()]
param(
    [ValidateSet('All', 'Backend', 'Frontend', 'BackendCompile', 'BackendTest', 'FrontendLint', 'FrontendTest', 'FrontendBuild')]
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

# 统一执行单个验收步骤：使用原生调用兼容 .exe/.cmd/.ps1，捕获全部输出，成功仅报一行，失败展开全文并终止。
function Invoke-QuietStep {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$WorkingDir,
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$PassThroughSingleLine
    )

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
            # 后端 Surefire 通过时尝试汇总 Tests run 计数，让一行摘要更具信息量。
            $summaryHint = ''
            if ($combined -match 'Tests run:\s*\d+') {
                $matches = [regex]::Matches($combined, 'Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)')
                if ($matches.Count -gt 0) {
                    $last = $matches[$matches.Count - 1]
                    $summaryHint = " | Tests run: $($last.Groups[1].Value), Failures: $($last.Groups[2].Value), Errors: $($last.Groups[3].Value), Skipped: $($last.Groups[4].Value)"
                }
            }
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
    Invoke-QuietStep -Label $label -WorkingDir $verifyBackend -Command 'mvn' -Arguments $mvnArgs
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

Write-Host "[OK] 静默验收完成（Scope=$Scope）；不代表真实数据库/Redis、浏览器或线上部署验收通过。"
