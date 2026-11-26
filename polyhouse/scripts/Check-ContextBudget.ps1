<#
.SYNOPSIS
上下文与文档预算自动体检脚本（只读，执行耗时约 0.2 秒）。
.DESCRIPTION
自动核查 6 项防膨胀与防黑盒护栏：
1. 宪法哨兵：检查 AGENTS.md 是否超出硬上限，以及工作区/当前分支是否存在改动并高亮预警；
2. DEV_LOG.md 体量上限与黄线预警；
3. DEV_LOG.md「🔒 永久钉选区」完整性检查，防止关键红线被误删；
4. 当前活跃阶段日志 v1.0-dev.<N>.md 的行数与体积切分阈值检查；
5. 默认必读三件套（AGENTS.md + DEV_LOG.md + 当前摘要）总字符预算检查；
6. 核心文档相对 Markdown 链接 0 断链检查。
#>
[CmdletBinding()]
param(
    [int]$AgentsMaxChars = 5800,
    [int]$DevLogMaxChars = 2500,
    [int]$DevLogWarnChars = 2200,
    [int]$ActiveLogMaxLines = 250,
    [int]$ActiveLogMaxBytes = 40960,
    [int]$MandatoryTotalMaxChars = 8000,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

$failures = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

function Read-Utf8File {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    $fullPath = Join-Path $repoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        $failures.Add("缺少必需文档：$RelativePath")
        return $null
    }
    $text = Get-Content -Raw -Encoding UTF8 -LiteralPath $fullPath
    if ($null -eq $text) { $text = '' }
    $lines = ($text -split "`r?`n").Count
    $bytes = [System.Text.Encoding]::UTF8.GetByteCount($text)
    return [PSCustomObject]@{
        RelativePath = $RelativePath
        FullPath     = $fullPath
        Text         = $text
        Chars        = $text.Length
        Lines        = $lines
        Bytes        = $bytes
    }
}

# 1. 读取核心入口文档
$agentsDoc = Read-Utf8File 'AGENTS.md'
$devLogDoc = Read-Utf8File 'DEV_LOG.md'

if ($null -ne $agentsDoc) {
    if ($agentsDoc.Chars -gt $AgentsMaxChars) {
        $failures.Add("AGENTS.md 超出硬上限：$($agentsDoc.Chars) / $AgentsMaxChars 字符，请合并条款或下沉细则至 docs/operations/。")
    }

    # 宪法哨兵：只要工作区或当前短期分支相对 dev 改动了 AGENTS.md，就必须高亮提醒，防止黑盒夹带修改。
    if (Get-Command git -ErrorAction SilentlyContinue) {
        Push-Location -LiteralPath $repoRoot
        $prevPref = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $wtStatus = (& git status --porcelain -- AGENTS.md 2>$null | Out-String).Trim()
            if ($wtStatus) {
                $warnings.Add("[CONSTITUTION] 检测到工作区存在未提交的 AGENTS.md 修改（$wtStatus），请确认已获用户逐字批准并单独提交，勿与普通业务混提。")
            }

            $branchName = (& git rev-parse --abbrev-ref HEAD 2>$null | Out-String).Trim()
            $hasDevRef = (& git rev-parse --verify dev 2>$null | Out-String).Trim()
            if ($branchName -and $branchName -ne 'dev' -and $branchName -ne 'main' -and $hasDevRef) {
                $diffStat = (& git diff --numstat dev..HEAD -- AGENTS.md 2>$null | Out-String).Trim()
                if ($diffStat) {
                    $parts = $diffStat -split '\s+'
                    $added = if ($parts.Count -ge 1) { $parts[0] } else { '?' }
                    $removed = if ($parts.Count -ge 2) { $parts[1] } else { '?' }
                    $warnings.Add("[CONSTITUTION] 当前分支 ($branchName) 相对 dev 修改了 AGENTS.md（+$added/-$removed 行），合入前请确认已获用户逐字白盒审定。")
                }
            }
        } finally {
            $ErrorActionPreference = $prevPref
            Pop-Location
        }
    }
}

# 2 & 3. 检查 DEV_LOG.md 体量预算与永久钉选区完整性
$activeLogRelPath = $null
$activeLogLabel = '未定位'
if ($null -ne $devLogDoc) {
    if ($devLogDoc.Chars -gt $DevLogMaxChars) {
        $failures.Add("DEV_LOG.md 超出上限：$($devLogDoc.Chars) / $DevLogMaxChars 字符，请将较早阶段滚入 docs/dev-log/ARCHIVE.md。")
    } elseif ($devLogDoc.Chars -gt $DevLogWarnChars) {
        $warnings.Add("DEV_LOG.md 接近预算上限（$($devLogDoc.Chars) / $DevLogMaxChars 字符），建议下次切阶段时提前滚动旧条目至 ARCHIVE.md。")
    }

    # 校验「🔒 永久钉选区」锚点及关键红线条款是否完好
    $pinnedMatch = [regex]::Match($devLogDoc.Text, '(?ms)^##\s+🔒\s*永久钉选区[^\r\n]*\r?\n(.*?)(?=^##\s+|\z)')
    if (-not $pinnedMatch.Success) {
        $failures.Add('DEV_LOG.md 缺少「## 🔒 永久钉选区」锚点，存在长期红线意外丢失风险。')
    } else {
        $pinnedBody = $pinnedMatch.Groups[1].Value
        $pinnedBullets = [regex]::Matches($pinnedBody, '(?m)^-\s+\S+')
        if ($pinnedBullets.Count -lt 3) {
            $failures.Add("DEV_LOG.md「🔒 永久钉选区」条目过少（仅 $($pinnedBullets.Count) 条），请核查是否误删关键约束。")
        }
        foreach ($keyword in @('屋主', '冻结', '授权')) {
            if ($pinnedBody -notmatch [regex]::Escape($keyword)) {
                $failures.Add("DEV_LOG.md「🔒 永久钉选区」缺少关键红线词「$keyword」，请核查是否误删。")
            }
        }
    }

    # 自动定位当前活跃阶段日志路径
    $stageMatch = [regex]::Match($devLogDoc.Text, 'docs/dev-log/v1\.0-dev\.(\d+)\.md')
    if ($stageMatch.Success) {
        $activeLogRelPath = $stageMatch.Value
        $activeLogLabel = "dev.$($stageMatch.Groups[1].Value)"
    } else {
        $failures.Add('DEV_LOG.md 未能解析出当前阶段日志链接（docs/dev-log/v1.0-dev.<N>.md）。')
    }
}

# 4 & 5. 检查当前活跃阶段日志切分阈值 & 必读三件套总预算
$activeLogDoc = $null
$activeSummaryChars = 0
if ($activeLogRelPath) {
    $activeLogDoc = Read-Utf8File $activeLogRelPath
    if ($null -ne $activeLogDoc) {
        if ($activeLogDoc.Lines -gt $ActiveLogMaxLines -or $activeLogDoc.Bytes -gt $ActiveLogMaxBytes) {
            $kb = ('{0:F1}KB' -f ($activeLogDoc.Bytes / 1024))
            $warnings.Add("当前阶段日志 $activeLogRelPath 已达 $($activeLogDoc.Lines) 行 / $kb（阈值 $ActiveLogMaxLines 行 / 40KB），请在最近一个完整交付节点后冻结并切分下一阶段。")
        }
        $summaryMatch = [regex]::Match($activeLogDoc.Text, '(?ms)^##\s+当前摘要[^\r\n]*\r?\n(.*?)(?=^---|\r?\n##\s+|\z)')
        if ($summaryMatch.Success) {
            $activeSummaryChars = $summaryMatch.Value.Length
        }
    }
}

$mandatoryTotalChars = 0
if ($null -ne $agentsDoc) { $mandatoryTotalChars += $agentsDoc.Chars }
if ($null -ne $devLogDoc) { $mandatoryTotalChars += $devLogDoc.Chars }
$mandatoryTotalChars += $activeSummaryChars
if ($mandatoryTotalChars -gt $MandatoryTotalMaxChars) {
    $warnings.Add("默认必读三件套总计 $mandatoryTotalChars 字符（超过目标 $MandatoryTotalMaxChars 字符），请检查当前摘要或 DEV_LOG.md 是否有冗余（安全与任务必需阅读不受截断）。")
}

# 6. 核心文档相对 Markdown 链接 0 断链校验
$linkTargets = [System.Collections.Generic.List[string]]::new()
foreach ($rel in @('AGENTS.md', 'DEV_LOG.md', 'docs/dev-log/ARCHIVE.md', 'docs/plans/README.md', 'docs/operations/verification.md')) {
    $linkTargets.Add($rel)
}
if ($activeLogRelPath -and -not $linkTargets.Contains($activeLogRelPath)) {
    $linkTargets.Add($activeLogRelPath)
}

$brokenCount = 0
foreach ($rel in $linkTargets) {
    $full = Join-Path $repoRoot $rel
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
        $failures.Add("核心索引缺失：$rel")
        continue
    }
    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $full
    $docDir = Split-Path -Parent $full
    $matches = [regex]::Matches($content, '\[[^\]]+\]\(([^)]+)\)')
    foreach ($m in $matches) {
        $rawHref = $m.Groups[1].Value.Trim()
        if (-not $rawHref -or $rawHref.StartsWith('#') -or $rawHref -match '^(https?|mailto|file):') {
            continue
        }
        $pathPart = ($rawHref -split '#')[0]
        if (-not $pathPart) { continue }
        $resolved = [System.IO.Path]::GetFullPath((Join-Path $docDir $pathPart))
        if (-not (Test-Path -LiteralPath $resolved)) {
            $brokenCount++
            $failures.Add("断链：$rel -> $rawHref")
        }
    }
}

$sw.Stop()
$elapsed = ('{0:F2}s' -f $sw.Elapsed.TotalSeconds)

# 无论是否 Quiet，所有警告（特别是宪法哨兵预警）一律输出，绝不隐藏。
foreach ($w in $warnings) {
    Write-Host "[WARN] $w" -ForegroundColor Yellow
}

if ($failures.Count -gt 0) {
    Write-Host "[FAIL] 上下文健康体检未通过（$($failures.Count) 项失败，$elapsed）：" -ForegroundColor Red
    foreach ($f in $failures) {
        Write-Host "  - $f" -ForegroundColor Red
    }
    exit 1
}

$activeStat = if ($null -ne $activeLogDoc) {
    "$activeLogLabel $($activeLogDoc.Lines)行/$('{0:F1}KB' -f ($activeLogDoc.Bytes / 1024))"
} else {
    $activeLogLabel
}
$agentsStat = if ($null -ne $agentsDoc) { "$($agentsDoc.Chars)/$AgentsMaxChars" } else { '?' }
$devLogStat = if ($null -ne $devLogDoc) { "$($devLogDoc.Chars)/$DevLogMaxChars" } else { '?' }

Write-Host "[PASS] 上下文健康体检 ($elapsed | AGENTS $agentsStat, DEV_LOG $devLogStat, 必读总计 $mandatoryTotalChars/$MandatoryTotalMaxChars, 当前 $activeStat, 断链 $brokenCount)"
exit 0
