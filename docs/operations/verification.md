# 验证入口

按 [AGENTS.md](../../AGENTS.md) 的改动影响选择检查：仅文档不运行构建。以下是可用入口，不要求每轮全部运行。已有正式屋主，禁止在开发库执行不带隔离专项的全量 OWNER 测试，不能为测试清空屋主记录。

项目级 UI/UX 开发辅助技能已固定为 v2.15.0，只供开发时按需查询，不属于网页运行依赖。来源、文件清单、停用/恢复与更新方式见[专门记录](../../docs/operations/ui-ux-skill.md)；从仓库根目录执行 `python -B polyhouse/scripts/Check-UiUxSkill.py` 可只读检查完整性。安装不会自动改网站或替代 `AGENTS.md`。

项目根目录可执行统一普通验收（先准备 JDK 21、Maven、满足 `package.json` 的 Node 版本，并在 `polyhouse-web/` 执行 `npm ci` 安装锁定依赖）：

```powershell
.\polyhouse\scripts\Test-Project.ps1
# 静默模式（通过仅打印单行摘要，失败自动展开完整日志，推荐日常与 AI 协作使用）
.\polyhouse\scripts\Test-Project.ps1 -Quiet
# 或按需分域/定向静默验证（Scope 支持 All/Context/Backend/Frontend/BackendCompile/BackendTest/FrontendLint/FrontendTest/FrontendBuild）
.\polyhouse\scripts\Test-Quiet.ps1 -Scope Frontend
.\polyhouse\scripts\Test-Quiet.ps1 -Scope BackendTest -Filter TrendInsightCodecTest
# 0.2 秒只读上下文健康体检（宪法哨兵 + 文档预算 + 永久钉选区 + 切分阈值 + 0 断链）
.\polyhouse\scripts\Check-ContextBudget.ps1
```

按顺序执行后端编译、普通测试、前端只检查不修复的 Oxlint 与 ESLint、`npm test`、类型检查及生产构建；任何阶段失败立即停止，并恢复终端工作目录。不读取 IDEA 私有凭据、不自动安装/迁移/授权/部署，也不自动打开真实实例、浏览器或星潮专项测试。真实 MySQL/Redis 验收只通过明确的隔离专项执行（安全 `-AccountSecurityOnly`、开户 `-AccountProvisioningOnly`、管理 `-AccountManagementOnly`、审核 `-ProfileModerationOnly`，均配合 `-Action Verify`）；默认普通验收中被关闭的实例测试会报告跳过，不能把它当作真实连接验收成功。

测试目录按业务组织，归类约束见 [AGENTS.md](../../AGENTS.md)：

| 测试根目录 | 当前归类 |
| --- | --- |
| [前端 tests](../../polyhouse-web/tests) | `helpers/`：跨模块共享测试运行时；`common/`：文件入口与核心隔离规则；`user/`：认证、账号/管理工作台、资料、卡面与成长；`trend/`：星潮阅读、窗口、简报与分页；`replace/`：妙替内核、批处理、词库与草稿；`target-kb/`：妙压内核、协议、文件与任务生命周期 |
| [后端 Java 测试根](../../polyhouse/src/test/java/com/polyethene/polyhouse) | `user/`：用户业务（含 `fixture/IsolatedMysqlSandbox` 与 `acceptance/`）；`trend/`：星潮归档、窗口、简报、缓存、限流与任务；`common/`：公共契约；`ops/`：运维命令与唤醒脚本；`system/`：应用启动、HTTP 与跨模块容量恢复 |

后端三个测试保留生产代码同包位置：`ops/OwnerBootstrapCommandTest` 测试包可见命令解析，`service/AvatarServiceTest` 调用包可见审核方法，`service/impl/AuthServiceImplTest` 使用包可见时钟构造器；不为目录归类扩大接口。校验夹具 `model/dto/ValidationProbeDTO`、`model/vo/ValidationProbeVO` 保留 DTO/VO 契约包，其他夹具随业务归类。

前端 `npm test` 递归发现全部业务目录；在 `polyhouse-web/` 可定向执行 `node --test "tests/user/*.test.mjs"`、`node --test "tests/trend/*.test.mjs"`、`node --test "tests/replace/*.test.mjs"` 或 `node --test "tests/target-kb/*.test.mjs"`。后端仍按类名选择，例如在 `polyhouse/` 执行 `mvn -q '-Dtest=AuthWebMvcTest' test`；现有隔离脚本按类名定位，不因业务目录迁移改用未隔离的全量实例测试。重排测试包后须清理旧编译测试类，避免 Maven 把旧、新包都发现为测试。

前端跨模块测试共用 [测试运行时夹具](../../polyhouse-web/tests/helpers/test-runtime.mjs)（提供内存编译 TS/SFC、`mountHeadless` 自动卸载及按原属性描述符/存在性还原的 `patchGlobal`），[妙替模块加载帮助文件](../../polyhouse-web/tests/replace/helpers/typescript-module.mjs)作为薄重导出保持兼容，不额外缓存模块或共享模拟状态；它们均不是测试发现入口，仍使用上面的原命令。后端 `user/` 域全部 MySQL 集成测试共用 [随机隔离库夹具](../../polyhouse/src/test/java/com/polyethene/polyhouse/user/fixture/IsolatedMysqlSandbox.java)，每次独立创建并销毁随机库，绝不触碰本机正式屋主数据。

## 测试编写范式与分层边界

遵循 [AGENTS.md](../../AGENTS.md) 的测试编写红线，具体落地口径如下：

1. **三层验收边界**：
   - **L1 离线单测 / 组件桩测**：验证纯算法、校验规则、状态机与组件契约，离线秒级可跑；
   - **L2 隔离真实例集成**：通过 `Invoke-LocalAuth.ps1 -Action Verify` 专项开关连接随机隔离 MySQL、本机/临时 Redis 与真实 HTTP，验证 SQL、事务、行锁、Lua 与鉴权闭环；
   - **L3 真实产物与端到端**：Docker 最终镜像产物、真实浏览器串联与 GitHub Actions 远端工作流。
   L1 通过绝不等于 L2/L3 验收通过。
2. **表驱动与断言口径**：
   - 后端使用 JUnit 5 `@ParameterizedTest`（`@ValueSource` / `@CsvSource` / `@MethodSource`）；前端继续沿用 Node 内置 `node:test`（无 `test.each`），在单个 `test(...)` 内用用例表配 `for (const item of cases) { await t.test(item.name, ...) }`。
   - 不写纯 getter/setter、纯字段容器或框架样板测试；对带 Jakarta Validation、反序列化白名单或敏感字段脱敏契约的 DTO/VO，按契约测非法与脱敏边界。
   - 不在组件测试中手改内部 `ref` 绕过初始化，不匹配 `.vue` CSS 源码字符串，不以内部调用次序镜像测试替代对外结果与副作用断言。
3. **确定性、隔离与红绿复核**：
   - 后端注入可控 `Clock` / `CountDownLatch`，前端用 `deferred` / `t.mock.method(Date, 'now', ...)` / 事件驱动，禁止靠 `Thread.sleep` 或固定 `setTimeout` 赌异步时序（确需等待限流窗口恢复时按响应头 `Retry-After` 有界轮询）。
   - 全局替身、监听器、组件挂载、临时文件与随机库/键必须在 `t.after()` 或 `@AfterEach` 彻底还原（原不存在的 `globalThis` 属性须 `delete` 而非赋 `undefined`）。
   - 新增 Bug 修复或精简存量测试后，须做红绿复核（临时破坏目标逻辑 → 对应测试必红 → 恢复后全绿），确保断言覆盖标题承诺的全部行为且防线未变弱。

`npm run lint:check` 已包含 `core/`、`workers/` 的局部隔离检查：拒绝 UI/响应式/页面状态层的直接依赖及主线程对象访问，保留 Worker 编码与消息能力。规则写在现有 [ESLint 配置](../../polyhouse-web/eslint.config.ts)，[回归样例](../../polyhouse-web/tests/common/core-boundary.test.mjs)只在内存解析，不写临时源文件。它检查可静态识别的依赖地址和常见全局写法，不是传递依赖分析或安全沙箱；原工程约束继续有效。使用现有 [导入限制](https://eslint.org/docs/latest/rules/no-restricted-imports)、[语法限制](https://eslint.org/docs/latest/rules/no-restricted-syntax)和全局/属性限制能力，未安装新插件。

U4 页面组合验收可显式使用 `Invoke-LocalAuth.ps1 -Action Verify -ProfileModerationOnly -WorkspaceBrowser`；配合独立 5175 Vite 和本机 5190 桥接，由受控浏览器操作当前界面/真实隔离接口，结束后核对数据库事实并清理，步骤与边界见[资料审核的 U4 验收入口](../../docs/operations/profile-moderation.md#u4-当前页面组合验收受控浏览器)。

有限负载与故障恢复可单独执行 `.\polyhouse\scripts\Test-CapacityRecovery.ps1`：复用 Git 忽略的私有预设，仅选择隔离验收类，需要本机 MySQL 创建临时库权限及 Ubuntu WSL Redis。它创建并清理随机临时库和 `target` 头像目录，只断开测试专用转发连接，不停止数据库/Redis，不运行 OWNER 夹具或修改开发库。普通验收不启用此测试。妙替完整本地流水线基准在 `polyhouse-web/` 执行 `node benchmarks/poly-replace-pipeline.mjs`，结果写入忽略的 `polyhouse/target/polyreplace-pipeline-results.json`；它测量 Node Worker 进程而非浏览器峰值内存。方法、实测和限制见[容量与恢复验收](../../docs/operations/capacity-recovery-2026-10-28.md)。

使用 JDK 21，在 `polyhouse/` 中执行：

```powershell
mvn -q compile -DskipTests
mvn -q test
```

在 `polyhouse-web/` 中执行：

```powershell
npm run build
npm test
npm run lint:check
```

编译通过和 Spring 上下文启动成功不等于数据库或 Redis 已完成连接验证；实际数据读写需要另行集成测试。普通测试不会执行 MySQL 查询，MySQL 和 Redis 实例测试默认跳过。

技术雷达`./polyhouse/scripts/Invoke-LocalAuth.ps1 -Action Verify -TrendOnly`仅验证随机MySQL库，含归档、预算、任务/费用、定时窗口/身份及回退，不运行账号/OWNER夹具、不连接真实Redis/AI。内容Lua另用`-Action Verify -TrendRedisOnly`验证本机随机Redis键，不碰正式内容/认证键、不连MySQL；自行管理Redis加`-SkipWslRedis`，临时端口可加`-TrendRedisPort`。普通测试不启用`polyhouse.trend.integration`、`polyhouse.trend.redis`或`polyhouse.trend.live`。入口/边界见[模块说明](github-tech-radar.md)，Java夹具归`trend/`，包装以[安全说明](deepseek-key-safety.md)的假后端脚本验，不传真实Key。

## 自动化浏览器访问本机服务

开发终端显示 Vite `ready`，只说明进程完成启动。浏览器连接超时时，先在实际运行浏览器的宿主机终端检查监听和 HTTP 响应，避免把不同隔离环境的结果混用：

```powershell
Get-NetTCPConnection -LocalPort 5173 -State Listen
curl.exe --noproxy '*' --max-time 8 -o NUL -w '%{http_code}' http://127.0.0.1:5173/polyethene-polyhouse/
```

本轮 Windows 验收中，受限沙箱启动的 Vite 在宿主机可见监听，但宿主机 HTTP 请求和内置浏览器都无法连接。停止那一次验收进程，改在宿主机终端进入隔离副本的 `polyhouse-web/`，执行 `npm run dev -- --host 127.0.0.1 --strictPort` 后，同端口请求返回 200，内置浏览器也正常加载。工具启动时按其权限流程允许这次本机服务运行；这是运行环境边界处理，无需修改 Vite 配置、切换浏览器或关闭防火墙。详细实测见[dev.14](../dev-log/v1.0-dev.14.md#f1-浏览器验收收口2026-11-14)。

若端口属于其他任务，先识别进程归属，再给本轮验收选空闲端口。后端也应在能连到本机 MySQL/Redis、且前端代理可达的环境中启动。结束后停止本轮进程并清理本轮隔离资源。
