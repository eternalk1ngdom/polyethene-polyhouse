<#
.SYNOPSIS
复用私有预设，只运行隔离数据库中的有限负载和故障恢复验收。
.DESCRIPTION
需要本机 MySQL 建库权限。测试创建并精确清理随机临时库及 target 图片，不修改开发库数据；
故障只施加于专用转发连接，不停止 MySQL/Redis。普通 Test-Project 不启用此验收。
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$capacityPreviousArgs = [Environment]::GetEnvironmentVariable('MAVEN_ARGS', 'Process')
$capacityKeeper = $null
try {
    # 本机 Maven 的入口必须实际识别 MAVEN_ARGS，避免选择项未生效而运行默认全量实例测试。
    $capacityMaven = (Get-Command mvn -ErrorAction Stop).Source
    if (-not (Select-String -LiteralPath $capacityMaven -SimpleMatch '%MAVEN_ARGS%' -Quiet)) {
        throw '当前 Maven 入口不支持已核对的测试选择方式，拒绝执行。'
    }
    $env:MAVEN_ARGS = '-Dtest=CapacityRecoveryIntegrationTest -Dpolyhouse.capacity.integration=true -DskipTests=false -Dmaven.test.skip=false'
    $capacityKeeper = Start-Process -FilePath (Get-Command wsl.exe -ErrorAction Stop).Source `
        -ArgumentList @('-d', 'Ubuntu', '--exec', 'sleep', 'infinity') -WindowStyle Hidden -PassThru
    & (Join-Path $PSScriptRoot 'Invoke-LocalAuth.ps1') -Action Verify
} finally {
    if ($null -ne $capacityKeeper -and -not $capacityKeeper.HasExited) { Stop-Process -Id $capacityKeeper.Id }
    if ($null -eq $capacityPreviousArgs) {
        if (Test-Path -LiteralPath Env:MAVEN_ARGS) { Remove-Item -LiteralPath Env:MAVEN_ARGS }
    } else { $env:MAVEN_ARGS = $capacityPreviousArgs }
}
