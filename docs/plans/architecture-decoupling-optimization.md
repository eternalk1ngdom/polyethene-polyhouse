# 聚妙屋架构解耦止血与 AI 协作降噪计划

创建时间：2026-11-25（Asia/Shanghai）。状态：**待确认批准（尚未开始编码）**。  
依据文档：[架构解耦与 AI 协作友好度专项诊断报告](../design/architecture-decoupling-assessment.md)（基线 `dev` @ `5118cfc`）。

## 一、立项必要性判断与范围边界

- **是否需要专门立项**：**有必要立一个“轻量、短闭环”的小计划，但不做停工大重构。**
  - 诊断显示全仓底层架构已较健康（双端 0 类/文件环、0 `any`、0 裸 `Map`），不存在必须大动干戈的系统性债；
  - 但其中 **2 项 P0 止血项**（跨端数量硬编码会在后续新增卡面/分类时直接引发 `502` 熔断；4 处硬编码墙钟阻碍跨日/过期确定性测试）以及 **2 项极低风险的 AI 协作降噪项**（拆分 731 行 `api/trend.ts`、清理 21 行 `AvatarService` 纯透传空壳）改动面小、**零 UI/布局/视觉影响**、可独立验证与秒级回退，集中用 2 个小批次收口投入产出比最高。
- **严守边界**：
  1. 严格遵守 [AGENTS.md](../../AGENTS.md) 铁律：不升级任何前后端依赖、不改 `vite.config.ts`、不改任何页面布局/样式/交互、不删除前端运行时响应校验、不改变对外 HTTP 契约与安全语义。
  2. 每条（或每批）独立提交、独立验证、独立可回退；不夹带重型重构或既有延后项。

---

## 二、按条例顺序排列的实施清单

以下 4 项严格按**风险止血优先 → AI 协作 ROI 优先**排序，拆为 2 个独立批次推进（可按批次逐一批准执行）：

### 批次一 · P0 风险止血（契约去硬编码 + 后端时钟统一）

#### 1. 解除跨端数量硬编码契约雷点（高优先 · 跨端低风险）
- **问题与证据**：
  - [profileCovers.ts](../../polyhouse-web/src/api/profileCovers.ts)（L60）硬编码 `covers.length !== 3`，后端未来新增第 4 款官方卡面会导致前端直接抛出 `502 卡面资料暂时无法确认`。
  - [api/trend.ts](../../polyhouse-web/src/api/trend.ts)（L515）硬编码 `list(row.categories, 8, windowCategoryStat)`，与分类清单脱钩；[TrendInsightCodec.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/trend/TrendInsightCodec.java)（L28 / L79）同时在 `CATEGORIES` 常量与 Prompt 文本中各手写一份 8 大类清单。
- **最小改法**：
  1. `profileCovers.ts`：保留 `isKey` / `reasons` 与单条项强校验，将 `covers.length !== 3` 调整为“至少 1 项且 `key` 唯一，且 `equipped` 必须命中返回列表中的某一项”（消除固定数量 3 的硬绑定；若后端未来先上线新卡面键，可按需进一步讨论未知 key 容错策略，本步先解除数量锁死并补单测）。
  2. `TrendInsightCodec.java`：保留固定顺序常量（如 `List<String>` / `LinkedHashSet`），Prompt 中分类枚举串由该常量拼接生成，消除后端单端双份维护（要求生成的 Prompt 文本逐字节与原版一致，不改变模型口径）。
  3. `api/trend.ts`：消除 `list(row.categories, 8, ...)` 的裸魔法数 `8`，改为有语义的命名上界常量（如 `MAX_WINDOW_CATEGORIES`，说明与分类白名单的同步关系，并允许未来温和扩展而不立即触发 502）。
- **验证要求**：
  - 后端：`cd polyhouse; mvn -q compile -DskipTests`，执行 `TrendInsightCodecTest` 等相关单测（验证 Prompt 与校验行为逐字节等价）。
  - 前端：`cd polyhouse-web; npm run lint:check; npm test; npm run build`，补充表驱动边界测试覆盖卡面数量变化与去重校验（遵循红绿复核）。
- **回退策略**：本条独立提交，按提交回退即可恢复。

#### 2. 统一后端剩余 4 个服务的系统时钟注入（高优先 · 后端低风险）
- **问题与证据**：容器内已有 `Clock` Bean 且 `ProfileCoverService`、`ProfileModerationService`、`TrendOwnerAuthorization` 已正确注入，但以下 4 处仍硬编码 `Instant.now()`：
  - [AccountManagementService.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/AccountManagementService.java)（L155）
  - [AccountProvisioningService.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/AccountProvisioningService.java)（L124）
  - [AccountSecurityService.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/AccountSecurityService.java)（L53）
  - [TrendArchiveService.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/TrendArchiveService.java)（L66）
- **最小改法**：
  - 4 个服务统一新增 `@Autowired` 注入 `Clock` 的构造器，并将上述 4 处 `Instant.now()` 替换为 `clock.instant()`；
  - **严格保留原无 `Clock` 参数的兼容构造器**（内部委托 `Clock.systemUTC()`），确保 `TrendArchiveService` 的 8 个集成测试类及账号服务既有单测零破坏。
- **验证要求**：
  - `cd polyhouse; mvn -q compile -DskipTests`，执行 `AccountManagementServiceTest`、`AccountProvisioningServiceTest`、`AccountSecurityServiceTest` 及星潮归档相关单测，补充 1～2 组固定 `Clock` 断言验证 JWT 过期/发布时间受注入时钟控制（红绿复核）。
- **回退策略**：本条独立提交，零数据库/接口变化。

---

### 批次二 · 高 ROI AI 协作降噪（可选独立闭环，零 UI 变化）

#### 3. 按子域拆分前端 `api/trend.ts`（中优先 · 最高 AI 上下文减负收益）
- **问题与证据**：[api/trend.ts](../../polyhouse-web/src/api/trend.ts) 共 731 行，将「日榜阅读 + 7 日窗口统计 + 7 日 AI 简报 + 屋主任务管理 + 费用账本」5 个子域的全部类型与手写运行时解码器揉在一起；全仓共 11 个生产文件、4 个测试文件引用它，任意子域小改都要读取整份 731 行上下文。
- **最小改法**：
  - 在 `polyhouse-web/src/api/` 下按子域拆分为清晰小模块（如 `trendCodec.ts` 公共解码基础、`trendReport.ts` 日榜与 7 日窗口、`trendBriefing.ts` 7 日简报、`trendAdmin.ts` 屋主任务与费用）；
  - 原 `polyhouse-web/src/api/trend.ts` 保留为**精简兼容重导出入口（barrel）**，所有既有 15 处调用方及测试导入路径零破坏；后续新代码与修改可按需定向读取单个 150～220 行子模块，AI 单次上下文削减约 60%。
  - 保留全部手写运行时解码与校验逻辑，逐字段行为 100% 等价。
- **验证要求**：
  - `cd polyhouse-web; npm run lint:check; npm test; npm run build`（重点核对 `tests/trend/*.test.mjs` 的 TS 内存转译模块加载器能完整解析拆分后的相对导入）。
- **回退策略**：纯前端模块拆分，独立提交即可一键回退。

#### 4. 清理 21 行纯透传空壳 `AvatarService`（低～中优先 · 消除冗余跳转）
- **问题与证据**：[AvatarService.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/AvatarService.java) 仅 21 行，唯一方法 `upload()` 原样转发 `ProfileModerationService.submitAvatar(request, token)`，全仓仅 4 处引用（`AvatarController`、`AvatarService`、`AvatarWebMvcTest`、`ProfileModerationIntegrationTest`）。
- **最小改法**：
  - 将 `ProfileModerationService.submitAvatar(...)` 改为 `public`（保持既有校验、会话锁与事务语义完全不变）；
  - `AvatarController` 直接注入并调用 `ProfileModerationService`；
  - 删除 `AvatarService.java`，同步更新 `AvatarWebMvcTest` 与 `ProfileModerationIntegrationTest` 的依赖注入（不删减任何断言）。
- **验证要求**：
  - `cd polyhouse; mvn -q compile -DskipTests`，执行 `AvatarWebMvcTest`、`ProfileModerationServiceTest` 等受影响单测；说明 `ProfileModerationIntegrationTest` 为隔离库专项，本步核对测试源码编译通过（如需真库运行再按开关单独触发）。
- **回退策略**：单提交恢复 `AvatarService.java` 与 3 处引用。

---

## 三、明确不纳入本次专项的项（条件触发与保留基线）

为避免过度设计或为“指标好看”引入额外回归风险，以下诊断项**明确不列入本次专门计划**：

| 类别 | 项目 | 不立即实施的原因与触发条件 |
| --- | --- | --- |
| **条件触发项** | `TargetKbPanel.vue` 抽离 `useTargetKbSession` | 维持 [framework-assessment.md](../design/framework-assessment.md) F5 策略；仅在下次有妙压功能需求时顺势抽离，并同步摆脱 SFC 字符串替换测试。 |
| **条件触发项** | `ProfileModerationService` 内联 Lua 外置 | 迁出至 `resources/redis/` 须联动 `.dockerignore` 与容器镜像实测，无配额改动需求时不单独触碰发布白名单。 |
| **条件触发项** | 后端 `TrendCollection` / `TrendOwnerAuthorization` 包归属整理 | 仅包级双向边、零类环与零运行风险；待星潮流水线后续有实质代码变动时顺手归位。 |
| **既有延后项** | `#7/#8/#9/#11/#12/#13` | 沿用 [repository-maintenance-backlog.md](repository-maintenance-backlog.md) 既定延后策略，不重复立项。 |
| **明确保留项** | 现有单体与扁平三层、`config <-> interceptor`、`Mapper -> QueryDTO`、`core/`/`workers/` 独立协议、`TrendWindowOverview`/`AccountView` 样式块、前端手写运行时校验 | 均完全贴合本项目当前规模或受铁律保护，**不要乱动**。 |

---

## 四、批准与执行入口

- 请确认采纳范围：**仅执行批次一（条目 1～2）**，或**依次执行批次一 + 批次二（条目 1～4）**；
- 批准后从本地 `dev` 新建短期分支（如 `chore/architecture-decoupling-hardening`），按条目顺序逐一实现、按影响验证并独立提交，完成后同步更新 [v1.0-dev.22.md](../dev-log/v1.0-dev.22.md) 与 [DEV_LOG.md](../../DEV_LOG.md)。
