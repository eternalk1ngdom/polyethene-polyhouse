# 上下文与 Token 降本瘦身及防膨胀治理计划（dev.23）

> 所属阶段：[v1.0-dev.23.md](../dev-log/v1.0-dev.23.md) · 基线：`dev` @ `fb7fd55` · 性质：工程效能与协作治理专项（零业务代码、零 UI 改动、零依赖升级）

## 一、背景与根因诊断

1. **基线损耗**：
   - `AGENTS.md`（每轮常驻系统提示）：77 行 / 7,106 字符 / 16.6 KB；
   - `DEV_LOG.md`（每会话必读入口）：60 行 / 4,451 字符 / 7.2 KB（大幅超出 2,500 字符上限）；
   - `npm test` 每次打印 ~500 行通过用例名；中间提交频繁同步多份文档导致额外往返轮次。
2. **历史教训与双极端风险**：
   - `git log AGENTS.md` 显示该文件累计被改 30 次；曾有瘦身提交 `b4f0db1` 仅 3 个提交后就被迫在 `2c0c82c` 补救恢复误删条款。
   - 仅靠一次性手工瘦身既会“治标不治本”（未来随阶段号增长必然再次膨胀），若交给 AI 黑盒压缩又存在信息悄悄丢失与偏离原意的风险。

## 二、已批准的五件套平衡治理架构（三权分立）

经用户明确确认，按 **「防膨胀 + 防黑盒 + 零信息丢失」** 三重目标拆为 **批次 A（工程底座与护栏，不改 `AGENTS.md`）** 与 **批次 B（`AGENTS.md` 黄金基准白盒审定锁定，纯规则独立提交）**：

### 批次 A：非宪法工程底座与自动护栏
1. **冷热分层归档与滚动窗口（终结 `DEV_LOG.md` 线性膨胀）**：
   - 新建冷层总档 [docs/dev-log/ARCHIVE.md](../dev-log/ARCHIVE.md)（平时不读），完整保留 `dev.1` 起全部历史阶段可点击链接与早期环境备忘，实现**移出热区 ≠ 丢失信息**。
   - 重构 `DEV_LOG.md` 为三分区：**当前焦点** + **「🔒 永久钉选区（未经用户确认禁止删除）」** + **最近 4 期滚动窗口**；体积恒定在 1,800～2,150 字符，数学上不再随阶段增长膨胀。
2. **计划总索引分栏**：
   - 重构 [docs/plans/README.md](README.md) 为顶部「活跃与待授权计划」与底部「已收口历史计划」，保留全部历史批准决议。
3. **静默验收脚本落地与两处精准加固**：
   - `polyhouse-web/scripts/run-tests-quiet.mjs` + `npm run test:quiet`：仅自底向上匹配 `node:test` 官方统计前缀行（`ℹ` / `#`），防止测试正文日志误干扰，并补齐表驱动单测 `polyhouse-web/tests/common/run-tests-quiet.test.mjs`。
   - `polyhouse/scripts/Test-Quiet.ps1` + `Test-Project.ps1 -Quiet`：通过执行前后 Surefire XML 时间戳快照差值准确汇总本轮真实后端用例计数；新增 `Context` 域并在 `All` 自动执行体检。
4. **0.3 秒自动上下文体检脚本与宪法哨兵（`polyhouse/scripts/Check-ContextBudget.ps1`）**：
   - 6 项只读核查：① 宪法哨兵（检测工作区/分支是否改动 `AGENTS.md` 并高亮预警，核查 ≤ 5,800 字符）；② `DEV_LOG.md` ≤ 2,500 字符（> 2,200 黄灯）；③ `🔒 永久钉选区` 完整性与核心红线关键词；④ 当前活跃 `v1.0-dev.<N>.md` ≤ 250 行 / 40 KB 切分提醒；⑤ 默认必读三件套总计 ≤ 8,000 字符；⑥ 核心文档相对链接 0 断链。

### 批次 B：`AGENTS.md` 黄金基准版白盒审定与只读锁定（已完成，`66a33a6`）
- 按用户审定批准的「选项 A」：保留全部核心铁律与关键边界，补回此前瘦身削掉的 5 处细微约束，将动态脚本名等变动项解耦为固定门牌号，写入「宪法只读锁 + 白盒提案制 + 纯规则独立提交 + 永久钉选区禁私删 + 思考与待办简体中文」治理条款。
- 已以纯规则独立提交 `66a33a6`（`docs(constitution): 锁定 AGENTS.md 黄金基线与简体中文协作规范`）正式锁定（5,792 / 5,800 字符），此后禁止在普通任务中顺带修改 `AGENTS.md`。

## 三、验收标准

1. **脚本与单测全绿**：`.\polyhouse\scripts\Test-Quiet.ps1 -Scope Frontend`（含新单测 `run-tests-quiet.test.mjs`）、`-Scope BackendCompile`、`-Scope BackendTest -Filter TrendInsightCodecTest`（验证输出真实用例计数）、`-Scope Context` 全通过。
2. **红绿复核**：验证静默脚本失败展开返回非零码，并验证 `Check-ContextBudget.ps1` 的宪法哨兵确实能捕获当前分支对 `AGENTS.md` 的历史修改并输出高亮 `[WARN][CONSTITUTION]`。
3. **零断链与预算达标**：`DEV_LOG.md` 稳控在 2,200 字符以下，全部核心入口与 `ARCHIVE.md` 相对链接 0 断链。
