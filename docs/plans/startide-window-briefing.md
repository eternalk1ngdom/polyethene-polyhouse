# 星潮 2B：7 日风口简报（跨日语义综合）计划书

状态：**规划草案（待新会话确认细节后按 B1～B5 分闭环实施）**。基线：`dev` `b88ab20`（已含 2A 7 日统计、详情页 `radar-4`、前端精修 F1～F5）。

本计划承接 [startide-insight-quality.md](startide-insight-quality.md#第二步跨日聚合看大风口单篇稳定后的下一闭环) 中的 2B 候选项，结合开发者周刊（Console.dev / TLDR / HelloGitHub）的倒金字塔阅读实践与多文档 LLM 实名引用防幻觉架构，明确**为什么做、防哪些坑、怎么做最轻且最稳**。工程约束仍以根目录 [AGENTS.md](../../AGENTS.md) 为准。

---

## 一、价值判断：为什么值得做，以及防“伪需求”的四条红线

当前星潮已具备「单日精读（`radar-4`）」与「2A 7 日确定性统计（分类占比、多日上榜、涨星中位数）」。读者仍存在一个明确缺口：**看到 `AI_AGENT` 占了 45%，但不知道这批项目本周到底集中在解决什么具体问题（例如 IDE 插件编排、本地语音还是 Agent 沙箱）**。2A 的统计数字只能回答“哪类多”，只有跨项目语义综合才能回答“都在卷什么、共识与分歧在哪”。

但如果设计失当，2B 很容易退化为「用 AI 写正确废话」的鸡肋板块。因此本计划先立下 **4 条不可突破的硬红线**：

1. **硬样本门槛，绝不为凑数硬写**：后端强制校验 `coveredDays >= 3` 且具备 `trendSignal` 的 `radar-3`/`radar-4` 独立仓库数 `radar3ReadyCount >= 5`；不足直接拒绝生成。`3～4` 天显式标注「有限样本」，`≥5` 天为「充分样本」。
2. **强制双仓实名引用 + 服务端白名单核验**：每条风口主题必须实名引用 `2～5` 个本次 7 日窗口真实收录的仓库（`owner/repo`），服务端校验每一个引用都在输入快照白名单内，且跨主题不得完全复用同一组证据仓；前端将证据仓渲染为可点击的站内详情/GitHub 链接，读者 1 秒可验真伪。
3. **倒金字塔 60 秒导读，绝不抢占 2A 数字**：不另建孤立页面，直接置于「近 7 日风向」视图顶部（上方 60 秒 AI 综合 → 下方 2A 确定性统计）；某期未生成简报时**不展示空态占位噪音**，页面无缝保持纯净 2A 统计。
4. **极低成本、完全解耦、零外键污染**：复用现有 `DeepSeekGateway` 与 `tool_trend_ai_month` 10 元月预算，不引入新依赖、向量库或自动重试烧钱循环；单次仅 1 次模型调用（预计 ¥0.005～¥0.015），首期仅由屋主在发布台按需手动触发，同指纹输入零费用幂等复用。

---

## 二、总体架构与数据流

```text
已发布 7 日日报快照 (TrendReportService)
        │
        ▼
TrendWindowAggregator (现有 2A 纯计算，零改动契约)
        │
        ▼
TrendBriefingInputBuilder (无状态纯组装：筛选有界候选池 + 计算 SHA-256 指纹 + 校验硬门槛)
        │
        ▼
TrendBriefingLedger (独立表 + 共享 tool_trend_ai_month 10 元月锁，RESERVED -> SETTLED/UNKNOWN/CANCELLED)
        │
        ▼
DeepSeekGateway (复用现有官方客户端，JSON Mode，单次调用，无自动重发)
        │
        ▼
TrendBriefingCodec (briefing-1 严格键集/纯文本/长度/双仓白名单实名引用校验)
        │
        ├── 校验通过 ──► 同事务结算费用 + 写入 tool_trend_briefing 不可变有效稿
        └── 校验失败 ──► 按已知用量结算（不伪装零费用），保留原有有效旧稿
```

### 1. 数据库设计（仅追加 `polyhouse/sql/015_trend_briefings.sql`，不动 `010～014`）

不复用 `tool_trend_ai_request`（其 `fk_trend_ai_request_repo` 强约束单个 `repository_id`），新增两张同构极简表并共享 `tool_trend_ai_month`：

| 表名 | 主键与核心列 | 职责与约束 |
| --- | --- | --- |
| `tool_trend_briefing` | `anchor_date DATE PRIMARY KEY`、`window_start`、`window_end`、`covered_days`、`sample_status`、`source_fingerprint CHAR(64)`、`model`、`rule_version`、`active_request CHAR(36) NULL`、`payload JSON NULL`、`generated_at DATETIME(6) NULL` | 按北京锚点日存储最新有效简报与并发执行槽；`CHECK` 约束 `payload IS NULL OR JSON_TYPE(payload) = 'OBJECT'`、`covered_days BETWEEN 3 AND 7` |
| `tool_trend_briefing_request` | `id CHAR(36) PRIMARY KEY`、`month_key CHAR(7)`、`anchor_date DATE`、`source_fingerprint CHAR(64)`、`model`、`rule_version`、`state`、`reserved_micros`、`charged_micros`、`input_bound`、`output_bound`、`input_rate`、`output_rate`、`prompt_tokens`、`completion_tokens`、`outcome`、`created_at` | 完整计费事实表；外键指向 `tool_trend_ai_month(month_key)` 与 `tool_trend_briefing(anchor_date)`；与 `011` 保持一致的四态机 `CHECK` 约束 |

**锁序与并发安全**：
- 统一锁序：`tool_trend_briefing`（按 `anchor_date`）→ `tool_trend_ai_month`（按 `month_key`）→ `tool_trend_briefing_request`（按 `id`）。
- 与单仓账本仅在 `tool_trend_ai_month` 行锁上串行共享 10 元月上限与 `suspended` 熔断开关，无循环等锁可能。
- `TrendFeeService` 扩展识别 `tool_trend_briefing_request` 的待核对（`RESERVED` / `UNKNOWN`）记录，支持屋主按同一规则在 5 分钟后保守结清，避免超时后槽位永久锁死（具体字段兼容性在 B2 明确，保持既有单仓费用接口向后兼容）。

### 2. 结构化输出契约（`briefing-1`）与反幻觉护栏

输入给模型的材料严格有界（总请求体 `<= 24 KiB`，远低于 32 KiB 限制）：
- 窗口元信息：`windowStart`、`windowEnd`、`coveredDays`、`missingDates`、`sampleStatus`、分类占比摘要、多日上榜仓库列表。
- 候选仓库池：最多取 45 个去重仓库（优先保留有 `trendSignal`、多日上榜、涨星高的条目），每条只提供 `fullName`、`category`、`appearanceDays`、`latestStarsAdded`、`definition`、`trendSignal`（不喂 README 原文，极省输入 token）。

模型必须返回严格 JSON（纯文本无 Markdown，首见术语加中文括号解释，沿用 `radar-4` 写作风格）：

| 字段 | 约束 | 用途 |
| --- | --- | --- |
| `headline` | 必填，12～40 字符 | 一句话总览本周最显著的技术共性 |
| `overview` | 必填，60～220 字符 | 客观导语，交代核心聚焦点与样本边界 |
| `themes` | 必填数组，2～3 项 | 核心升温/聚集主题列表 |
| `themes[].title` | 必填，6～24 字符 | 主题标题（如“终端编码助手的插件化封装”） |
| `themes[].signalType` | 必填枚举：`CONCENTRATION`（多仓聚集）/ `RECURRING`（持续霸榜）/ `EMERGING`（新苗头） | 区分成熟聚集与早期信号，防止夸大 |
| `themes[].summary` | 必填，80～240 字符 | 跨项目综合说明：共同解决什么、路径有何差异、尚缺什么验证 |
| `themes[].evidenceRepos` | 必填数组，2～5 项 | **白名单强制校验**：必须逐项命中输入快照的真实仓库 `fullName`，自动归一化大小写，禁止幻觉仓与空数组，跨主题证据集不得完全相同 |
| `watchouts` | 必填数组，1～2 项，每项 ≤100 字符 | 冷静边界提醒（如缺采日、单明星仓拉动、仅反映 GitHub 日榜关注度） |

### 3. 接口与权限契约（三层隔离 + 铁律 4）

| 接口 | 权限与缓存 | 说明 |
| --- | --- | --- |
| `GET /api/trend/briefing?periodKey=YYYY-MM-DD` | `@SkipBearerAuth`（匿名只读，绝不触发生成） | 读取指定锚点日（默认最新已发布日）的 7 日风口简报 `TrendBriefingVO`；无简报返回 `200` 且 `data.status = "ABSENT"`（避免前端控制台 404 噪音），同时返回 `stale` 标记（当期日报若更新导致指纹变化则标为 `true`） |
| `POST /api/manage/trend/briefings` | `@RequireRole(UserRoleEnum.OWNER)` + `@RequireRole(UserRoleEnum.INSIDER)` + `Cache-Control: no-store` | 屋主按需触发生成（入参 `TrendBriefingGenerateDTO`：可选 `periodKey`、`forceRefresh` 默认 `false`）。先核对样本硬门槛与冷却限流；若同 `anchorDate` + 同 `sourceFingerprint` + 同模型规则已有有效简报且未强制刷新，直接零费用返回 `REUSED`；否则走账本预留并执行单次生成 |

> 说明：由于单次简报生成仅需 1 次 DeepSeek 调用（通常 3～12 秒），**B3 设计讨论时需最终确认**采用「屋主接口同步执行（复用 30s 超时 + 独立并发槽防重）」还是「复用异步 `202` 轮询模式」；推荐先做同步有界执行 + 独立并发槽（实现远轻于新建任务队列，失败/超时由账本兜底，前端按钮 loading 等待即可），如实测超时偏高再评估异步化。

### 4. 前端集成（最小侵入）

- **读者端**（[TrendWindowOverview.vue](../../polyhouse-web/src/features/trend/TrendWindowOverview.vue) + 新增 `TrendWindowBriefing.vue`）：
  - 在「近 7 日风向」视图懒加载时并行只读请求 `GET /api/trend/briefing`（失败静默降级，绝不阻断 2A 统计展示）。
  - 仅在 `status === "READY"` 时于统计指标上方渲染简报区块：清晰标注「AI 跨日综合 · 基于 N 天样本」、`headline`、`overview`、`themes` 卡片（附 `signalType` 轻标签与可点击的证据仓库链接，当期在榜跳站内详情、不在当期跳 GitHub 外链）以及底部的 `watchouts` 边界提醒；若 `stale === true` 显示柔和的“基于较早日报快照生成”提示。
- **屋主发布台**（[TrendOwnerView.vue](../../polyhouse-web/src/views/TrendOwnerView.vue)）：
  - 新增紧凑的「7 日风口简报」卡片：显示锚点日、当前样本覆盖是否达到门槛（`coveredDays` 与 `radar3ReadyCount`）、当前简报状态（未生成 / 已是最新 / 快照已更新）以及「生成本期风口简报」按钮与结果反馈。

---

## 三、分闭环实施路线（B1～B5）

| 闭环 | 范围与核心交付 | 必需验证 |
| --- | --- | --- |
| **B1 · 数据迁移与纯逻辑核心** | 新增 `015_trend_briefings.sql`；实现 `TrendBriefingInputBuilder`（候选池、指纹、硬门槛）与 `TrendBriefingCodec`（提示词、正面 Few-Shot、严格 JSON 与双仓白名单校验） | `mvn -q compile -DskipTests` + `TrendBriefingInputBuilderTest` + `TrendBriefingCodecTest`（覆盖非法仓名、重复键、Markdown 拒收、样本门槛拦截、指纹确定性） |
| **B2 · 独立账本与费用闭环** | 实现 `TrendBriefingMapper`、`TrendBriefingLedger`（共享 `tool_trend_ai_month` 10 元预算与四态机）；扩展 `TrendFeeService` 对待核对简报请求的只读展示与保守结算 | 隔离库集成测试 `TrendBriefingLedgerIntegrationTest`（预算共享、超限拒绝、并发锁、超时 UNKNOWN 与 5 分钟保守结算、015 迁移与回退检查） |
| **B3 · 服务层与接口契约** | 实现 `TrendBriefingService`、新增 `GET /api/trend/briefing` 与 `POST /api/manage/trend/briefings`（权限注解、`no-store`、冷却/限流、幂等指纹复用、可选 Redis 只读缓存） | `TrendBriefingServiceTest`、`TrendBriefingControllerTest`、HTTP 集成测试（验证匿名禁写、ADMIN 403、OWNER+INSIDER 正常、ABSENT/STALE 契约） |
| **B4 · 前端展示与屋主触发** | 扩展 `src/api/trend.ts` 严格解码；新增 `TrendWindowBriefing.vue` 接入 `TrendWindowOverview.vue`；`TrendOwnerView.vue` 增加按需触发卡片；补前端解码与渲染单测 | `npm run build`、`npm run lint:check`、`npm test`、Prettier 检查、多断点（`1366/768/520/320px`）浏览器验证 |
| **B5 · 真实小样本试跑与收口** | 确认开发库已有 ≥3～5 期 `radar-3`/`radar-4` 日报后，使用屋主身份真跑 1 次真实 DeepSeek 简报生成，人工盲评核对每条主题与证据仓真实性、核对费用（预计 ≤ ¥0.02），更新日志并合入 `dev` | 真实端到端浏览验收 + 费用账本核对 + 日志收口 |

---

## 四、进入实施前待用户确认的 4 个设计点

1. **样本硬门槛**：允许 `coveredDays >= 3 && radar3ReadyCount >= 5`（`3～4` 天标「有限样本」，`≥5` 天标「充分样本」，推荐，开发库很快能实测），还是严格要求 `>= 5` 天才允许生成？
2. **屋主触发模式**：同步有界执行（约 3～12 秒返回，代码极简、账本兜底，推荐），还是复用异步 `202` 轮询？
3. **强制刷新策略**：同一锚点日且输入指纹未变时，是否只允许零费用复用（防误点烧钱，推荐），还是保留一个显式 `forceRefresh` 复选框供屋主重抽？
4. **费用账本面板**：是否在现有「星潮发布台」的待核对费用面板里直接合并展示待核对的简报请求（统一用现有保守结算入口，推荐）？
