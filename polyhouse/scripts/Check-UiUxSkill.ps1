<#
.SYNOPSIS
只读核对项目级 UI/UX 技能的固定来源及完整文件清单。
.DESCRIPTION
不联网、不执行第三方脚本、不读取个人预设；缺失、意外修改或额外文件均报告失败。
Python 字节码缓存不属于安装内容，按技能目录的忽略规则排除。
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$skillRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$skillManifestPath = Join-Path $skillRepository 'docs/operations/ui-ux-skill-lock.json'
$skillManifest = Get-Content -LiteralPath $skillManifestPath -Raw | ConvertFrom-Json
if ($skillManifest.schemaVersion -ne 1 -or $skillManifest.skillName -ne 'ui-ux-pro-max' -or
    $skillManifest.installedDirectory -ne '.agents/skills/ui-ux-pro-max' -or
    $skillManifest.repository -ne 'https://github.com/nextlevelbuilder/ui-ux-pro-max-skill' -or
    $skillManifest.sourceCommit -notmatch '^[a-f0-9]{40}$') {
    throw '技能清单格式或来源异常，未读取任何技能脚本。'
}
$skillRoot = [IO.Path]::GetFullPath((Join-Path $skillRepository $skillManifest.installedDirectory))
if (-not (Test-Path -LiteralPath $skillRoot -PathType Container)) { throw '技能目录不存在或已停用。' }
if ((Get-Item -LiteralPath $skillRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
    throw '技能目录不应为符号链接或目录联接，请核对安装记录。'
}
$skillExpected = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($skillEntry in $skillManifest.files) {
    # 拒绝绝对路径、父目录和非规范分隔符，清单不能把检查引向个人配置等其他目录。
    if ($skillEntry.path -notmatch '^[a-zA-Z0-9_.-]+(/[a-zA-Z0-9_.-]+)*$' -or
        @($skillEntry.path -split '/' | Where-Object { $_ -in @('.', '..') }).Count -gt 0 -or
        -not $skillExpected.Add($skillEntry.path)) { throw '技能清单含非法或重复路径。' }
    $skillFile = [IO.Path]::GetFullPath((Join-Path $skillRoot $skillEntry.path))
    if (-not $skillFile.StartsWith($skillRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw '技能清单路径越界。'
    }
    if (-not (Test-Path -LiteralPath $skillFile -PathType Leaf)) { throw "缺少技能文件：$($skillEntry.path)" }
    if ((Get-Item -LiteralPath $skillFile -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "技能文件不应为符号链接：$($skillEntry.path)"
    }
    if ((Get-FileHash -LiteralPath $skillFile -Algorithm SHA256).Hash -ine $skillEntry.sha256) {
        throw "技能文件内容与已审核清单不符：$($skillEntry.path)"
    }
}
if (-not $skillExpected.Contains('SKILL.md') -or -not $skillExpected.Contains('scripts/search.py')) {
    throw '技能清单缺少入口或检索脚本，不能视为完整安装。'
}
foreach ($skillFile in Get-ChildItem -LiteralPath $skillRoot -File -Recurse -Force) {
    $skillRelative = [IO.Path]::GetRelativePath($skillRoot, $skillFile.FullName).Replace('\', '/')
    if ($skillRelative -match '(^|/)__pycache__/|\.py[cod]$') { continue }
    if (-not $skillExpected.Contains($skillRelative)) { throw "发现未登记技能文件：$skillRelative" }
}
[pscustomobject]@{
    Skill = $skillManifest.skillName
    Release = $skillManifest.release
    SourceCommit = $skillManifest.sourceCommit
    FilesVerified = $skillExpected.Count
    Scope = 'repository'
    ReadOnly = $true
}
