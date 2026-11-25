# 聚妙屋架构解耦与 AI 协作友好度专项诊断报告

评估时间：2026-11-25（Asia/Shanghai）。源码基线：`dev` @ `5118cfc`（工作区干净）。本轮严格执行[专项评估计划](../plans/architecture-decoupling-assessment.md)的只读边界，未修改任何业务源码、测试源码、SQL、配置或依赖；静态扫描已排除 `node_modules/`、`target/`、`dist/`、`.agents/skills/`、`.local-backups/`。

本报告保存本次体检的事实证据与 ROI 分级结论，不替代 [AGENTS.md](../../AGENTS.md)，并与既有[框架评估](framework-assessment.md)及[仓库维护计划](../plans/repository-maintenance-backlog.md)的延后项保持对齐。

---

## 核心结论与规模基线

- **全仓自有源码规模**：后端 `polyhouse/src/main/java` 共 **195 文件 / 11,853 行**（最大单文件仅 398 行）；前端 `polyhouse-web/src` 共 **98 文件 / 23,435 行**（最大单文件 1,129 行）。
- **总体判断**：整体架构处于**健康偏优**水平——双端**零类/文件级循环依赖**，前端 `core/`、`api/`、`config/` **零反向依赖**，前后端边界**零 `any` 与零裸 `Map`/无约束对象**，20+ 个较新后端服务均规避了无意义的单实现 `Impl` 抽象。当前主要摩擦点集中在**前端局部巨型文件/编排未下沉**（`TargetKbPanel.vue`、`api/trend.ts`、`TrendWindowOverview.vue`）、**2 处跨端数量硬编码契约雷点**，以及**后端 4 处绕过 `Clock` 的硬编码墙钟与少量包级双向边**；**无需推倒重来或全仓改目录**。

---

## 一、项目结构画像与依赖拓扑

### 1. 双端核心目录与实际职责对照

| 端 | 目录 / 包 | 文件数 | 总行数 | 实际职责与命名一致性 |
| --- | --- | ---: | ---: | --- |
| 后端 | `controller` | 12 | 593 | 仅负责路由、DTO `@Valid`、`@RequireRole`、`Cache-Control: no-store` 与 VO 包装；无业务堆砌。 |
| 后端 | `service` + `service/impl` | 24 | 3,511 | 账号/认证/成长/审核/卡面核心服务，以及 **12 个星潮编排服务**（与 `service/trend` 横向拆开）。 |
| 后端 | `service/trend` | 16 | 2,449 | 星潮采集、解析、AI 编解码、7 日窗口聚合与外部 HTTP 网关；内聚度高，但含 1 个反向依赖 `service` 的鉴权辅助类。 |
| 后端 | `mapper` | 16 | 860 | MyBatis 持久化与定向行锁 SQL；2 个查询 Mapper 直接引用 `model.dto` 作为筛选入参。 |
| 后端 | `model/{dto,entity,vo}` | 85 | 2,074 | 三层隔离执行严格；仅 `TrendCollection` 放在 `model.entity` 却嵌套 `model.vo.TrendProjectVO`。 |
| 后端 | `common/*` + `utils` | 20 | 1,262 | `utils/`（3 文件）保持无状态纯静态函数；`common/security` 为共享安全规则（依赖 `model.entity.User`）。 |
| 后端 | `config` + `interceptor` + `ops` | 21 | 1,090 | `config/` 同时承载 `@ConfigurationProperties`、参数解析器与 Spring MVC 装配，引发包级双向边。 |
| 前端 | `core` | 13 | 1,709 | 纯 TS 算法与协议（妙替、妙压、本地卡面裁剪），**出边为空（`[]`）**，严格符合铁律 7。 |
| 前端 | `workers` | 3 | 407 | Web Worker 编解码与批处理适配，仅依赖 `core` 与纯函数 `utils/byteSize.ts`。 |
| 前端 | `api` | 9 | 1,984 | HTTP 客户端、TS 契约与手写运行时响应校验，**内部出边为空（`[]`）**。 |
| 前端 | `features/trend` | 13 | 4,276 | 按业务域收拢的星潮阅读与发布台特性目录，边界清晰（仅依赖 `api`/`config`/`stores`/`assets`）。 |
| 前端 | `views` + `components` + `composables` | 42 | 12,939 | 页面与业务组件，是当前大文件集中区；妙压编排堆在 `TargetKbPanel.vue`，账号管理/开户/资料审核已抽离为 `composables/`。 |
| 前端 | `stores` + `utils` + `config` + `router` | 16 | 1,865 | 状态与轻工具；`utils/` 干净，未沦为业务逻辑垃圾桶。 |

### 2. 高层模块依赖流向图

```text
[后端包依赖流向]
controller ──▶ service ◀──(包级环1: TrendOwnerAuthorization)──▶ service.trend
    │             │                                                  │
    ▼             ▼                                                  ▼
model.dto      mapper ──(查询入参)──▶ model.dto                   config (Properties)
model.vo ◀──(包级环2: TrendCollection)──▶ model.entity
    ▲
    │
config (WebMvcConfig) ◀──(包级环3: AuthRequestArgumentResolver)──▶ interceptor (AuthInterceptor) ──▶ service (AuthService)

[前端目录依赖流向]
router ──▶ views ──▶ features/trend ──▶ api (无出边)
            │   └──▶ components ──▶ composables ──▶ stores ──▶ api / utils
            │              │                                       │
            │              └──▶ workers ──▶ core (无出边)          ▼
            │                     └──▶ utils/byteSize (纯函数)   core / api (仅 type)
            └──▶ config (无出边)

[前后端跨端边界]
polyhouse-web/src/api/*.ts  ──(HTTP JSON + 手写运行时校验)──▶  polyhouse/.../controller/*.java (DTO -> VO + Result<T>)
```

### 3. 拓扑隐患核查结果

- **类/文件级循环依赖**：**后端 0 个，前端 0 个**。
- **后端包级循环边（已核实 3 组根因，均非类环）**：
  1. `model.entity <-> model.vo`：[TrendCollection.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/model/entity/TrendCollection.java) 位于 `model.entity`，却嵌入 `model.vo.TrendProjectVO`；而 `UserProfileVO`、`AccountCreationVO` 等 6 个 VO 通过静态 `from(Entity)` 依赖 `model.entity`。
  2. `service <-> service.trend`：[TrendOwnerAuthorization.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/trend/TrendOwnerAuthorization.java) 位于 `service.trend` 并引用 `service.AuthRedisStore`，而根包 `service/` 下 11 个 `Trend*Service` 反向引用 `service.trend.*`。
  3. `config <-> interceptor -> service -> config`：[WebMvcConfig.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/config/WebMvcConfig.java) 注册 `AuthInterceptor` 并注入 `AuthService`，而 [AuthInterceptor.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/interceptor/AuthInterceptor.java) 引用同在 `config` 包的 `AuthRequestArgumentResolver`，各服务又读取 `config/*Properties`。属 Spring Boot 扁平 `config` 包的常见结构性双向边，无运行时风险。
- **底层/公共模块反向依赖核查**：
  - 后端 `utils/`：仅依赖 `common.constant` 与 `common.exception`（[AvatarImageUtils.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/utils/AvatarImageUtils.java) 抛 `BusinessException`），无 Bean/Service/Mapper 依赖，严格合规。
  - 前端 `core/`、`api/`、`config/`：出边均为 **0**。
  - 前端 `utils/`：[userPresentation.ts](../../polyhouse-web/src/utils/userPresentation.ts) 仅 `import type { UserProfile } from '../api/auth'`（编译期擦除）；[targetkb.worker.ts](../../polyhouse-web/src/workers/targetkb.worker.ts) 仅引用纯函数 [byteSize.ts](../../polyhouse-web/src/utils/byteSize.ts)（9 行，无宿主全局对象），未越界。

---

## 二、欠解耦（高耦合）热点排查

### 1. 全仓自有源码最大文件 Top 5 拆解

| 排名 | 文件 | 总行数 | 结构拆解（SFC / TS） | 混杂的变化职责与耦合评估 |
| ---: | --- | ---: | --- | --- |
| 1 | [TrendWindowOverview.vue](../../polyhouse-web/src/features/trend/TrendWindowOverview.vue) | **1,129** | script 117 / template 329 / **style 680** | **样式偏重型**，逻辑不重（仅展开态与比例计算），但 KPI 条、堆叠条、分类卡、多日连榜 4 块视图与 680 行 CSS 挤在同一 SFC 中，抬高 AI 局部阅读成本。 |
| 2 | [TargetKbPanel.vue](../../polyhouse-web/src/components/TargetKbPanel.vue) | **1,054** | **script 597** / template 220 / style 234 | **逻辑上帝组件**：同一 `<script setup>` 内混杂浏览器能力探测、文件去重/头解析、参数校验、Worker 生命周期与任务代次取消、单图/批量串行调度、fflate ZIP 打包、ObjectURL 释放、DOM `<a>` 下载与摘要格式化。 |
| 3 | [AccountView.vue](../../polyhouse-web/src/views/AccountView.vue) | **922** | script 138 / template 295 / **style 486** | **展示/布局样式型**，实际业务状态已拆给 `useUserStore`、`useUserGrowthStore`、`useProfileSubmissions` 与 `ProfileCoverPicker`，逻辑较轻。 |
| 4 | [PolyReplaceView.vue](../../polyhouse-web/src/views/PolyReplaceView.vue) | **780** | script 267 / template 244 / style 266 | 中等聚合：含文件勾选、键盘焦点归还、规则编辑与一次性 Worker 调度；词库、草稿、预览已拆分为独立子组件，整体可控。 |
| 5 | [api/trend.ts](../../polyhouse-web/src/api/trend.ts) | **731** | 纯 TS（类型 + 运行时校验 + 请求） | **多子域合包**：把「日榜阅读 + 7 日窗口统计 + 7 日 AI 简报 + 屋主任务管理 + 费用账本」5 个子域的 20+ 接口类型与全部手写字段解码器塞在同一文件，任意子域加字段都需读改整份文件。 |

> 后端最大单文件为 [TrendBriefingCodec.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/trend/TrendBriefingCodec.java)（398 行，含中文 Prompt 模板）与 [GithubTrendClient.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/trend/GithubTrendClient.java)（368 行），后端无超过 400 行的上帝类。

### 2. 跨边界越权访问与分层职责错位

- **跨模块越权访问**：未发现业务模块绕过边界直接篡改另一模块私有表或内部状态的情况。
- **分层职责错位（3 处实证）**：
  1. **UI 组件承载重型异步编排**：[TargetKbPanel.vue](../../polyhouse-web/src/components/TargetKbPanel.vue) 将 Worker/ZIP/ObjectURL 编排写死在 SFC 内，迫使生命周期测试 [target-kb-lifecycle.test.mjs](../../polyhouse-web/tests/target-kb/target-kb-lifecycle.test.mjs) 在运行时调用 `vue/compiler-sfc` 并对 `new URL('../workers/targetkb.worker.ts', import.meta.url)` 做源码字符串替换。
  2. **Redis Lua 脚本内联在核心服务**：[ProfileModerationService.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/ProfileModerationService.java) 直接注入 `StringRedisTemplate` 并内联 7 行 `ADVANCE_QUOTA` Lua 字符串，与 `AuthRedisStore`、`TrendReportCache` 外置 `.lua` 脚本并独立封装缓存适配的做法不一致。
  3. **Mapper 直接绑定查询 DTO**：[AccountManagementMapper.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/mapper/AccountManagementMapper.java) 与 `ProfileModerationMapper.java` 直接接收 `model.dto.*` 作为 `@Param` 并在动态 SQL 中引用其字段（属轻量查询务实权衡，但弱化了 Controller-Mapper 隔离）。

### 3. 副作用与隐式耦合

- **后端 4 处绕过已注入的 `Clock` Bean，硬编码 `Instant.now()`**：
  - [AccountManagementService.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/AccountManagementService.java)（L155）
  - [AccountProvisioningService.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/AccountProvisioningService.java)（L124）
  - [AccountSecurityService.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/AccountSecurityService.java)（L53）
  - [TrendArchiveService.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/TrendArchiveService.java)（L66）
  - 对比：`ProfileCoverService`、`ProfileModerationService`、`TrendOwnerAuthorization` 均已正确注入 `Clock`。
- **前端 `localStorage`/`sessionStorage` 散落 9 处**：分布在 `api/auth.ts`、`main.ts`、`stores/{useUserStore,replaceDraftStorage,ruleLibraryStorage}.ts`、`composables/{useAccountManagement,useAccountProvisioning,useReplaceDraft}.ts`、`features/trend/useTrendOwner.ts`，缺少统一键名索引。

---

## 三、过度解耦（过度抽象）红灯排查

### 1. 主业务链路跳转层数（Hop Count）

| 主业务链路 | 实际调用路径（前端触发 → 后端存储） | 跳转层数 | 评估 |
| --- | --- | ---: | --- |
| **星潮 7 日窗口统计读取** | `TrendWindowOverview` → `useTrendEdition` → `api/trend.getTrendWindow` → `TrendController.window` → `TrendReportService.window` → `TrendWindowAggregator` / `TrendMapper` | **5～6 跳** | 合理；每层有独立职责。 |
| **屋主开户** | `AccountProvisioningView` → `useAccountProvisioning` → `api/accountProvisioning` → `AccountProvisioningController.create` → `AccountProvisioningService.create` → `AccountProvisioningMapper` | **5 跳** | 合理；无空转发。 |
| **用户提交头像审核** | `AvatarUploadDialog` → `api/avatar` → `AvatarController.upload` → **`AvatarService.upload`** → `ProfileModerationService.submitAvatar` → `ProfileModerationMapper` | **6 跳（含 1 冗余跳）** | [AvatarService.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/AvatarService.java) 仅 21 行，唯一方法原样转发 `moderation.submitAvatar(request, token)`，零校验、零事务、零状态。 |

### 2. 单实现冗余抽象、模型转换膨胀与过早错误抽象

- **单实现接口控制极佳**：22 个后端业务服务中，仅最早期的 `AuthService`、`UserGrowthService` 保留 `Interface + Impl`，其余 20+ 新服务均直接具体类；`service/trend/` 的 `DeepSeekGateway` 与 `TrendSourceTransport` 是真实的外部 HTTP 防腐端口，并非过度设计。
- **透传空壳层**：全仓仅证实 `AvatarService`（21 行）1 处纯透传空壳。`TrendJobManagementService`（54 行）承担了 5 秒 `TransactionTemplate`、锁序与提交后唤醒 `runner.wake()`，非空壳。
- **模型转换膨胀**：后端 `Entity → VO` 为必要脱敏隔离；前端 [api/trend.ts](../../polyhouse-web/src/api/trend.ts) 手写 TS `interface` + 逐字段运行时解码器，在合包 731 行下放大了修改和阅读成本。
- **过早错误抽象**：未发现将本质无关业务靠大量 boolean 开关硬塞进共享函数/组件的情况。

---

## 四、契约边界、跨端一致性与外部依赖隔离

### 1. 边界类型安全

- **前端**：`src/` 全量扫描 **0 处 `any` / `as any` / `<any>`**，所有 HTTP 响应均经 `unknown` + 运行时校验收窄。
- **后端**：全部 `controller/`、`model/dto/`、`model/vo/` 扫描 **0 处裸 `Map` 或 `Object` 字段透传**，VO 普遍使用不可变 Java `record` 并冻结集合字段。

### 2. 前后端契约漂移（SSOT）盘点

| 契约项 | 后端定义位置 | 前端定义位置 | 风险判定 |
| --- | --- | --- | --- |
| **个人卡面 Key 与数量** | [ProfileCoverService.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/ProfileCoverService.java) | [api/profileCovers.ts](../../polyhouse-web/src/api/profileCovers.ts)（L60 硬编码 `covers.length !== 3`） | **高脆性雷点**：后端新增第 4 款官方卡面时前端直接抛 `502` 熔断整个卡面目录。 |
| **星潮 8 大技术分类** | [TrendInsightCodec.java](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/trend/TrendInsightCodec.java)（`CATEGORIES` 常量 + Prompt 文本 2 处） | [presentation.ts](../../polyhouse-web/src/features/trend/presentation.ts)（3 处映射）+ [api/trend.ts](../../polyhouse-web/src/api/trend.ts)（L515 硬编码列表上限 `8`） | **易漂移重复事实源**：共 6 处散落清单/上限，新增分类时前端 `list(row.categories, 8, ...)` 会触发 `502`。 |
| **角色、审核状态、基础表单规则** | `UserRoleEnum`、`ProfileReviewStatus`、DTO 注解 | `api/auth.ts`、`utils/userPresentation.ts`、`utils/authValidation.ts` | **必要的前后端双重防线**，变更极少，保持现状即可。 |

### 3. 外部依赖与宿主 API 防腐

- **良好收敛**：GitHub/DeepSeek HTTP 收敛在 `GithubTrendHttp` / `DeepSeekHttp`（网关接口隔离）；认证与星潮 Redis Lua 收敛在 `AuthRedisStore`、`TrendReportCache` 与 `resources/redis/*.lua`；前端 HTTP 统一在 `api/index.ts`，IndexedDB 唯一在 [localProfileCover.ts](../../polyhouse-web/src/utils/localProfileCover.ts)。
- **轻度渗漏**：`ProfileModerationService` 内联 Redis Lua；前端 `BroadcastChannel` 跨 `localProfileCover.ts` 与 `ProfileCoverPicker.vue` 两处；`localStorage`/`sessionStorage` 散落 9 处。

---

## 五、AI 协作友好度压力测试（真实场景推演）

### 场景 A（跨端核心业务）：星潮 7 日窗口统计新增 1 个分类展示字段 / 扩展 1 个新分类

- **新增 1 个分类展示字段**：最少需阅读 **6 个文件**（`TrendWindowStatsVO`、`TrendWindowAggregator`、`TrendReportService`、`api/trend.ts`、`TrendWindowOverview.vue`、`useTrendEdition.ts`），最少修改 **4 个生产文件** + 2～3 个测试文件。
- **核心摩擦点**：改动文件数不多，但上下文噪音集中在前端 2 个大文件——`api/trend.ts`（731 行，混杂无关的费用/任务解码器）与 `TrendWindowOverview.vue`（1,129 行，680 行 CSS）；若新增分类，还极易遗漏 `api/trend.ts:515` 的硬编码上限 `8` 与 `TrendInsightCodec` Prompt 文本。

### 场景 B（纯前端工具）：妙压（TargetKB）删掉内部实现、仅留外部契约能否独立重写？

- **删掉 [core/targetKbCompress.ts](../../polyhouse-web/src/core/targetKbCompress.ts) 内部实现**：**AI 可 100% 独立重写，0 外部文件改动**（纯函数、零宿主依赖、清晰契约 + 独立单测）。
- **删掉 [TargetKbPanel.vue](../../polyhouse-web/src/components/TargetKbPanel.vue) 编排实现**：**卡点显著**——597 行 `<script setup>` 混杂 UI/DOM/Worker/ZIP/ObjectURL，且 [target-kb-lifecycle.test.mjs](../../polyhouse-web/tests/target-kb/target-kb-lifecycle.test.mjs) 强耦合 SFC 源码中 `new URL('../workers/targetkb.worker.ts', import.meta.url)` 字面量替换。

---

## 六、五维健康度速览与按 ROI 分级的行动清单

### 1. 五维健康度速览表

| 评估维度 | 评级 | 一句话证据依据 |
| --- | --- | --- |
| **边界拓扑** | **健康** | 双端 0 类/文件级环，前端 `core/`/`api/`/`config/` 出边为 0；后端仅 3 组无运行风险的包级双向边。 |
| **分层深度** | **健康** | 主链路平均 **5.3 跳**（5～6 跳），仅 `AvatarService`（21 行）1 处历史透传空壳。 |
| **契约清晰度** | **轻度警告** | 类型安全极优（0 `any`、0 裸 `Map`），但存在 `profileCovers.ts` 硬编码长度 `3`、`api/trend.ts` 硬编码上限 `8` 等扩展雷点。 |
| **微观内聚** | **轻度警告** | 后端全部类 ≤398 行；前端 `TargetKbPanel.vue`（script 597 行）与 `api/trend.ts`（731 行）偏厚，推高 AI 阅读摩擦。 |
| **防过度设计** | **健康** | 20+ 新服务均弃用单实现 `Impl`，未强行合并两工具协议，克制度极高。 |

**关键量化数据**：最大文件 Top 5 为 `1,129 / 1,054 / 922 / 780 / 731` 行（后端最大 398 行）；主链路平均跳转 **5.3 跳**；单次典型跨端字段变更触达 **4 个生产文件**（最少读 6 个）。

### 2. 分级整改清单（与既有延后项对齐）

- **P0（优先止血，2 项）**：
  1. 解除跨端硬编码数量契约雷点（`profileCovers.ts:60` 去固定长度 3；`api/trend.ts:515` 分类上限与分类清单对齐；`TrendInsightCodec.java` 由 `CATEGORIES` 派生 Prompt 分类串）。
  2. 统一后端剩余 4 处硬编码 `Instant.now()` 为注入 `Clock`（保留兼容构造器避免破坏既有测试）。
- **P1（高 ROI 轻量降噪 & 随迭代顺手重构）**：
  - **适合随轻量计划顺带收口（高 ROI、零 UI 风险）**：按子域拆分 `polyhouse-web/src/api/trend.ts`（保留原文件兼容重导出）；清理 `AvatarService` 21 行空壳。
  - **条件触发 / 随后续需求顺手处理（不单独停工）**：`TargetKbPanel.vue` 抽离 `useTargetKbSession`（维持 [framework-assessment.md](framework-assessment.md) F5 试点策略）；`ProfileModerationService` 内联 Lua 外置（联动 `.dockerignore` 与镜像验收）；后端 `TrendCollection`/`TrendOwnerAuthorization` 包归属微调。
  - **既有延后项不重复立项**：[repository-maintenance-backlog.md](../plans/repository-maintenance-backlog.md) 的 `#7/#8/#9/#11/#12/#13` 继续按原策略延后观察。
- **明确保留项（不要乱动）**：现有单体架构与扁平三层、`config <-> interceptor` 包级双向边与 `Mapper -> QueryDTO` 轻量绑定、`core/`/`workers/` 隔离及两工具独立协议、`TrendWindowOverview.vue`/`AccountView.vue` 的 scoped CSS 样式块、以及前端零依赖手写运行时校验机制。

> 针对上述高 ROI 条目，已另起草轻量可独立回退的候选实施计划：[架构解耦止血与 AI 协作降噪计划](../plans/architecture-decoupling-optimization.md)（待用户确认批准后分批执行）。
