<#
.SYNOPSIS
一条命令完成前后端普通验收；不启用真实 MySQL/Redis 实例测试。
.DESCRIPTION
不读取 IDEA 私有预设，不执行迁移、授权、部署或自动安装，不修改源码格式。
阶段失败立即停止；真实实例验收仍由 Invoke-LocalAuth.ps1 单独显式执行。
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$verifyBackend = Split-Path -Parent $PSScriptRoot
$verifyRoot = Split-Path -Parent $verifyBackend
$verifyFrontend = Join-Path $verifyRoot 'polyhouse-web'

foreach ($verifyTool in @('mvn', 'java', 'node', 'npm')) {
    if (-not (Get-Command $verifyTool -ErrorAction SilentlyContinue)) {
        throw "缺少 $verifyTool，请按 README 准备 JDK 21、Maven 和前端运行环境后再执行。"
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $verifyFrontend 'node_modules') -PathType Container)) {
    throw '缺少前端依赖，请先在 polyhouse-web 中执行 npm ci；此验收入口不自动安装或升级。'
}

Push-Location -LiteralPath $verifyBackend
try {
    Write-Host '[1/5] 后端编译'
    & mvn -q compile -DskipTests
    if ($LASTEXITCODE -ne 0) { throw '后端编译失败，后续阶段未执行。' }

    Write-Host '[2/5] 后端普通测试（实例开关显式关闭）'
    # 不继承偶然打开的实例测试开关；同时明确要求执行普通测试，避免环境中的 skip 标志造成假成功。
    & mvn -q '-DskipTests=false' '-Dmaven.test.skip=false' '-Dpolyhouse.mysql.integration=false' `
        '-Dpolyhouse.redis.integration=false' '-Dpolyhouse.auth.integration=false' '-Dpolyhouse.jwt.runtime=false' test
    if ($LASTEXITCODE -ne 0) { throw '后端普通测试失败，后续阶段未执行。' }
} finally {
    Pop-Location
}

Push-Location -LiteralPath $verifyFrontend
try {
    Write-Host '[3/5] 前端静态检查（不自动修复）'
    & npm run lint:check
    if ($LASTEXITCODE -ne 0) { throw '前端静态检查失败，后续阶段未执行。' }

    Write-Host '[4/5] 前端自动测试'
    & npm test
    if ($LASTEXITCODE -ne 0) { throw '前端测试失败，后续阶段未执行。' }

    Write-Host '[5/5] 前端类型检查与生产构建'
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw '前端类型检查或生产构建失败。' }
} finally {
    Pop-Location
}

Write-Host '普通验收全部通过；这不代表真实数据库/Redis、浏览器、部署或业务功能验收通过。'
