# 测试体系升级与仓库维护修复验收计划

创建时间：2026-11-25（Asia/Shanghai）。关联分支：`chore/repository-maintenance`（基于本地 `dev` `f2241cc`）。关联前置计划：[仓库维护计划](repository-maintenance-backlog.md)。

## 一、背景与当前未验收状态

1. **已完成编码范围**：`chore/repository-maintenance` 已分三阶段完成仓库维护计划中的 10 项高价值改动（`#15/#17/#3/#16`、`#2/#4/#10/#1/#5`、`#6`，共 11 个本地提交 `1f3c232`～`76ff070`），并通过统一普通验收 `Test-Project.ps1`（后端普通测试、前端 Oxlint/ESLint、372 项 Node 测试与前端构建）。
2. **明确未验收边界（重点）**：**上述 10 项改动（尤其是用户可见 Bug 修复 `#1/#2/#4/#5/#10`、Docker 镜像 `#15`、Pages 发布 `#16` 与星潮口径 `#6`）的深度/真实验收尚未执行**——包括红绿变异复核、隔离真库/Redis 集成验收、Docker 镜像重建实测、真实浏览器串联走查及远端 Pages 运行。
3. **为什么先升级测试再验收**：当前仓库已有后端 95 个 Java 测试文件、前端 38 个 `.test.mjs` 测试文件，部分存量测试存在重复入参复制、贴着内部实现做镜像断言、Mock 过深或时序脆弱等问题。先统一测试规范并治理低价值/高噪音测试，再按新标准逐项验收本次修复，才能避免「测试跑绿但真实缺陷漏检」的假安全感。

---

## 二、聚妙屋测试规范（本专项执行标准 · 已确认）

> 说明：根目录 [AGENTS.md](../../AGENTS.md) 仍是唯一长期工程契约。以下 10 条规则经阶段 A 审计复核已拍板作为本专项的评审与编写标准；阶段 D 将仅把其中 5～8 行精简红线并入 `AGENTS.md`，详细写法与夹具范式沉淀到 [verification.md](../operations/verification.md)，不另立第二份长期契约。

### 核心六条（结合本仓技术栈落地）

1. **契约优先于实现**：断言必须严格基于业务规则、HTTP/VO 契约、状态结果与对外可见副作用编写；严禁照抄内部私有步骤、用过度细碎的 `verify`/`inOrder` 调用次序或正则/子串匹配 `.vue` CSS 源码做镜像测试，也严禁在组件测试中手写内部状态 ref 来跳过初始化契约。
2. **禁止琐碎测试**：不为简单 getter/setter、纯字段容器 DTO、无分支透传封装或框架默认样板代码写单元测试；但对带 Jakarta Validation 注解、反序列化白名单或敏感字段脱敏契约的 DTO/VO，应按契约测边界而非测存取器。
3. **默认使用表驱动测试**：同一行为的多组输入变体（正常值、边界值、非法值）必须合并为单个参数化/表驱动测试，禁止仅改入参就复制多个重复测试方法，禁止在单方法内用无名 `for` 循环吞掉后置失败定位，也禁止用 `reset(mock)` 在单方法内堆叠多个独立场景。
   - **后端（JUnit 5）**：直接使用 `spring-boot-starter-test` 自带的 `@ParameterizedTest`（配合 `@ValueSource` / `@CsvSource` / `@MethodSource`）。
   - **前端（Node 内置 `node:test`）**：本仓不使用 Jest/Vitest，**`node:test` 不存在 `test.each`**；统一在单个 `test(...)` 内使用用例表配合 `for (const item of cases) { await t.test(item.name, ...) }`（或带清晰用例名的断言消息）。
4. **严格限制 Mock 边界**：只 Mock 外部 I/O 边界（网络/HTTP、时钟 `Clock`、第三方 API、必要的持久化 Mapper 替身、Node 环境下不可用的浏览器宿主 API）；严禁 Mock 被测单元本身、纯算法内核（`polyhouse-web/src/core/`、后端无状态聚合器/编解码器）或简单值对象。
5. **验证失败能力（红绿循环）**：新增 Bug 修复或核心业务测试时，必须实际验证「临时破坏目标逻辑 → 测试必然失败 → 恢复后通过」；严禁为强行跑通而放宽或削弱既有断言，且断言必须覆盖测试标题承诺的全部行为（防“标题写了但测试体漏测”的空壳防线）。合并或精简旧测试时同样需做红绿抽检，确保防线未变弱。
6. **聚焦分支与边界场景**：重点覆盖空值/空集合、差一边界（off-by-one）、长度/容量上限、异常路径与状态流转，不盲目追求 100% 行覆盖率数字。

### 针对本项目特征补充的四条

7. **分层验收，禁止用桩测冒充真实验收**：明确三层边界——
   - **L1 离线单测 / 组件桩测**：验证纯算法、规则校验与状态机，快速且离线可跑；
   - **L2 隔离真实例集成**：连接随机隔离 MySQL、本机 Redis 与真实 HTTP，验证 SQL/事务/行锁/Lua/鉴权闭环；
   - **L3 真实产物与端到端**：Docker 最终镜像、真实浏览器 + 真实后端、GitHub Actions 远端工作流。
   L1 通过绝不等于 L2/L3 验收通过。
8. **确定性与强隔离（零脆弱、零污染）**：
   - 禁止依赖真实墙钟时间或用固定 `setTimeout` 赌异步时序（后端注入 `Clock`，前端用 `deferred` / 可控时钟 / 事件通知驱动）；
   - 禁止跨用例共享可变全局状态，所有 `globalThis` 替身、模块级共享 stub、临时文件、Redis 键与随机库必须在 `t.after()` / `@AfterEach` 彻底清理（原本不存在的 `globalThis` 属性须 `delete` 还原而非赋 `undefined`）；L2 数据库集成测试统一接入随机隔离库，绝对不触碰本机正式屋主数据。
9. **并发竞态、取消隔离与失败回滚**：针对本项目高频状态机（卡面并发扣款、头像/妙压/妙替取消或卸载后迟到结果防复活、星潮 AI 预留与未知态结算、跨标签存储同步），高风险模块必须覆盖并发冲突、中断隔离与半途失败回滚，而非只测单次顺序执行。
10. **安全与鉴权负向契约**：凡涉及登录态、角色管理、私有资源、文件导入导出或动态查询的测试，必须成对覆盖负向场景（未登录 401、角色越权 403、跨账号访问拒绝、响应不含 `passwordHash`/`credentialVersion` 等敏感字段、私有响应带 `Cache-Control: no-store`、非法路径/控制字符拦截）。

---

## 三、分阶段待办清单与推进节奏

推进原则：**先审后改、先立尺子再精简、逐批红绿验证、最后完成 10 项修复的真实闭环验收**；每完成一个阶段独立提交并汇报确认，不攒大改动。

### 阶段 A · 规范拍板与存量测试体检（只读审计，不改代码）

- [x] **A1 · 确认测试规范口径**：已核对 10 条规范并补充 4 条实审细则，用户确认无冲突且阶段 D 仅并入精简红线到 `AGENTS.md`、细则沉淀至 `verification.md`。
- [x] **A2 · 存量测试分级审计**：已完成全仓 134 个文件（后端 95 Java + 前端 39 `.mjs`）100% 只读审计，明确分级改造列表；用户确认全部方案，含阶段 B2 将 `user/` 6 个旧 L2 测试一并迁入随机隔离库、阶段 B3 升格前端公共测试夹具 `tests/helpers/`。
- **完成标志**：已完成并获用户确认，进入阶段 B。

### 阶段 B · 测试体系升级与噪音治理（按模块分批，红绿兜底）

- [x] **B1 · 治理本轮新增测试中的脆弱点与表驱动化**：已完成 7 个本轮新增/改动测试文件升级（[trend-edition.test.mjs](../../polyhouse-web/tests/trend/trend-edition.test.mjs) 改可控 `Date.now` 差一边界去 65ms 墙钟等待，[profile-cover-picker.test.mjs](../../polyhouse-web/tests/user/profile-cover-picker.test.mjs) 去手改 `ready.value` 与 8 处 `setTimeout(0)`，[avatar-editor.test.mjs](../../polyhouse-web/tests/user/avatar-editor.test.mjs) 坏文件分支改 `t.test()` 表驱动并补 4096/动图/慢解码竞态，[local-profile-cover.test.mjs](../../polyhouse-web/tests/user/local-profile-cover.test.mjs) 广播异常表驱动并补损坏记录/防复活/`onblocked`，[target-kb-lifecycle.test.mjs](../../polyhouse-web/tests/target-kb/target-kb-lifecycle.test.mjs) 补齐 `t.after` 隔离；[TrendContentCacheTest.java](../../polyhouse/src/test/java/com/polyethene/polyhouse/trend/TrendContentCacheTest.java) 参数化与 `Clock.fixed`，[TrendWindowIdentityTest.java](../../polyhouse/src/test/java/com/polyethene/polyhouse/trend/TrendWindowIdentityTest.java) 补 7 日切片/同日去重/非法 ID 参数化），并完成 4 处前端与 1 处后端红绿变异复核。
- [x] **B2 · 后端存量测试分批治理**：按 `common/system/ops` → `user` → `trend` 完成全后端存量测试治理（**B2-1** `common/system/ops` + `AuthServiceImplTest`；**B2-2** `user/` 全域治理、共享 `IsolatedMysqlSandbox` 抽离及 10 个 MySQL L2 迁入随机隔离库；**B2-3** `trend/` 20 文件去 `reset`/内部计数钩子/只读 `verifyNoMoreInteractions`、限流恢复按 `Retry-After` 有界轮询、L2/L3 时钟确定性加固及循环参数化）；三批均完成红绿变异复核与离线 L1 全量回归，全仓 94 个后端测试类零告警编译通过。
- [x] **B3 · 前端存量测试分批治理**：已完成跨模块共享夹具升格与全前端存量测试治理（**B3-1** 新增共享 `tests/helpers/test-runtime.mjs`，完成 `trend/` 与 `user/` 9 文件去 CSS 镜像、补 2 处标题漏测、修 `globalThis` 泄漏与表驱动化；**B3-2** 完成 `target-kb/` 6 文件去重与 `t.test()` 表驱动化、`replace/` 5 文件 P0 `hosts` 共享状态污染修复与 `t.after`/`patchGlobal` 隔离）；两批均完成红绿变异复核、`npm run lint:check` 0 告警 0 错误及 `npm test` 464/464 全通过。
- **完成标志**：无效/重复测试明显收敛、测试运行稳定无偶发超时、抽样破坏关键业务规则时对应测试 100% 稳定报红。

### 阶段 C · 本轮仓库维护 10 项修复的完整专项验收（已完成，除 `#16` 远端首轮发布项外全通过）

| 验收项 | 对应修复 | 验收层级 | 具体验收动作与通过标准 | 当前状态 |
| --- | --- | --- | --- | --- |
| **C1** | `#1/#2/#4/#5/#6/#10/#15/#17` | L1 红绿复核 | 逐项临时回退/破坏修复逻辑，确认对应测试必红；恢复后全绿，确认回归测试非空壳断言。期间发现 `TrendContentCacheTest` 未断言降级日志含异常简单类名与脱敏，已原地补齐 `OutputCaptureExtension` 断言并通过。 | **已通过** |
| **C2** | `#6/#10/#15` | L2 隔离真实例 | 运行 `-Action Verify -TrendOnly`（60/60）、`-TrendRedisOnly`（3/3）、`-ProfileModerationOnly`（19/19，2 项可选浏览器用例跳过），实测真实 MySQL/Redis/HTTP 下的窗口口径、卡面行锁并发与缓存读写/回退。 | **已通过** |
| **C3** | `#15` | L3 Docker 镜像 | 本机重建 Docker 镜像 `polyhouse-verify:c3-lua`，核实 `/app/app.jar` 仅含 `application.yml`、`application-prod.yml` 与 3 份 Redis Lua（`auth_remove_session.lua`、`trend_read_view.lua`、`trend_write_view.lua`），无私密配置；容器内连隔离 Redis 实测写入与二次读取命中，停机后返回降级并打印 `RedisConnectionFailureException`，临时容器与镜像已清理。 | **已通过** |
| **C4** | `#1/#2/#4/#5/#10` | L3 真实浏览器串联 | 使用生产构建产物 `polyhouse-web/dist/` + 隔离接口服务（`http://127.0.0.1:18473`）在真实浏览器串测全部 5 项：① 个人页回焦零多余 `GET /api/profile/covers`（`#10`）；② 头像已有草稿改选 GIF 后预览清空、提交禁用且上传计数为 0，重选静态图恢复可提交（`#1`）；③ 妙压批量 3 图夹杂损坏 PNG 完成 2 成功 1 失败并正常生成可用 ZIP（`#2`）；④ 星潮「更早日期」503 后保留原页并由「重试历史」沿用游标 `2026-11-24` 追加第 2 页（`#4`）；⑤ 本地卡面双标签保存、切换官方/本地实时同步且无报错（`#5`）。 | **已通过** |
| **C5** | `#3/#16/#17` | L3 脚本与发布门禁 | 注入全部 16 项 `MAVEN_OPTS` 污染开关实跑 `Test-Project.ps1` 全 5 步通过（后端 761 通过/177 跳过、前端 `lint:check` 0 告警、Node 464/464、`npm run build` 成功）（`#3`）；故意制造 Oxlint `no-constant-condition` 违规被 `lint:check` 拦截返回退出码 1 并回退（`#17`）；`#16` 远端 Pages 双作业运行登记为「获批合入 `main` 后首轮发布验收项」。 | **已通过**（`#16` 远端已登记） |

- **完成标志**：已完成（除需获批合入 `main` 才触发的 `#16` 远端 Pages 双作业实跑外全部实测通过），可进入阶段 D。

### 阶段 D · 验收收口、规范沉淀与分支晋级

- [x] **D1 · 沉淀测试规范**：已按“精简高效”原则在 [AGENTS.md](../../AGENTS.md)「按影响验证」节并入 4 行测试编写红线，并将 L1/L2/L3 分层边界、表驱动写法、确定性/隔离/红绿范式与共享夹具（`tests/helpers/test-runtime.mjs`、`IsolatedMysqlSandbox`）沉淀至 [verification.md](../operations/verification.md)，同步修正其中过时的 `AuthControllerTest` 与测试目录说明。
- [x] **D2 · 全量回归与日志收口**：基于 C5 已在相同代码基线全量通过的 `.\polyhouse\scripts\Test-Project.ps1`（本步仅改文档，按契约不重复跑 Maven/npm），已完成 `v1.0-dev.21.md`（约 115 行，未达 250 行切分阈值）与 `DEV_LOG.md` 收口及本地链接/差异核对。
- [ ] **D3 · 分支合并与远端同步（须另行确认）**：已汇总最终提交范围与验收证据，待你明确确认后执行合并入 `dev`、推送远端及后续 `main` 晋级。

---

## 四、风险与边界提醒

- **防误删有效保护网**：存量测试治理绝不以“删掉多少用例”为 KPI；凡不确定用途的安全/并发/边界断言，必须先查对应源码和历史日志再决定合并或保留。
- **不扩大范围、不升依赖**：整个测试升级与验收过程不升级 `pom.xml`、`package.json` 依赖版本（不引入 Vitest/Jest 等新测试框架），继续沿用 JUnit 5 + `node:test`。
- **保护本机正式屋主库**：阶段 C2/C4 的真实例验收一律通过随机隔离库与独立端口执行，禁止直接对本机正式开发库跑写入或清空夹具。
