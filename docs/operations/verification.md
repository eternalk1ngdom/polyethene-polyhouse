# 验证入口

按 [AGENTS.md](../../AGENTS.md) 的改动影响选择检查：仅文档不运行构建。以下是可用入口，不要求每轮全部运行。已有正式屋主，禁止在开发库执行不带隔离专项的全量 OWNER 测试，不能为测试清空屋主记录。

项目级 UI/UX 开发辅助技能已固定为 v2.15.0，只供开发时按需查询，不属于网页运行依赖。来源、文件清单、停用/恢复与更新方式见[专门记录](../../docs/operations/ui-ux-skill.md)；从仓库根目录执行 `python -B polyhouse/scripts/Check-UiUxSkill.py` 可只读检查完整性。安装不会自动改网站或替代 `AGENTS.md`。

项目根目录可执行统一普通验收（先准备 JDK 21、Maven、满足 `package.json` 的 Node 版本，并在 `polyhouse-web/` 执行 `npm ci` 安装锁定依赖）：

```powershell
.\polyhouse\scripts\Test-Project.ps1
```

按顺序执行后端编译、普通测试、前端只检查不修复的 ESLint、`npm test`、类型检查及生产构建；任何阶段失败立即停止，并恢复终端工作目录。不读取 IDEA 私有凭据、不自动安装/迁移/授权/部署，也不自动打开真实实例测试。真实 MySQL/Redis 验收只通过明确的隔离专项执行（安全 `-AccountSecurityOnly`、开户 `-AccountProvisioningOnly`、管理 `-AccountManagementOnly`、审核 `-ProfileModerationOnly`，均配合 `-Action Verify`）；默认普通验收中被关闭的实例测试会报告跳过，不能把它当作真实连接验收成功。

测试目录按业务组织，归类约束见 [AGENTS.md](../../AGENTS.md)：

| 测试根目录 | 当前归类 |
| --- | --- |
| [前端 tests](../../polyhouse-web/tests) | `common/`：文件入口与核心隔离规则；`user/`：认证、账号/管理工作台、资料与成长；`replace/`：妙替内核、批处理、词库与草稿；`target-kb/`：妙压内核、协议、文件与任务生命周期 |
| [后端 Java 测试根](../../polyhouse/src/test/java/com/polyethene/polyhouse) | `user/`：用户业务，内部沿用 controller/service 等分层；`common/`：公共契约；`system/`：应用启动、Redis 连接与跨模块容量恢复 |

后端三个测试保留生产代码同包位置：`ops/OwnerBootstrapCommandTest` 测试包可见命令解析，`service/AvatarServiceTest` 调用包可见审核方法，`service/impl/AuthServiceImplTest` 使用包可见时钟构造器；不为目录归类扩大接口。校验夹具 `model/dto/ValidationProbeDTO`、`model/vo/ValidationProbeVO` 保留 DTO/VO 契约包，其他夹具随业务归类。

前端 `npm test` 递归发现全部业务目录；在 `polyhouse-web/` 可定向执行 `node --test "tests/user/*.test.mjs"` 、`node --test "tests/replace/*.test.mjs"` 或 `node --test "tests/target-kb/*.test.mjs"`。后端仍按类名选择，例如在 `polyhouse/` 执行 `mvn -q '-Dtest=AuthControllerTest' test`；现有隔离脚本按类名定位，不因业务目录迁移改用未隔离的全量实例测试。重排测试包后须清理旧编译测试类，避免 Maven 把旧、新包都发现为测试。

妙替部分测试共用 [TypeScript 模块加载帮助文件](../../polyhouse-web/tests/replace/helpers/typescript-module.mjs)，只集中编译设置和地址替换，不额外缓存模块或共享模拟状态；它不是测试发现入口，仍使用上面的原命令。

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

技术雷达专项 `./polyhouse/scripts/Invoke-LocalAuth.ps1 -Action Verify -TrendOnly` 在随机G1/G2/G3a/G3b费用隔离库验证迁移、归档、预算、任务围栏、并发、保守费用关闭与回退，不运行账号/OWNER夹具、不连接Redis或付费AI。普通测试不会开启 `polyhouse.trend.integration` 或 `polyhouse.trend.live`；按[模块说明](github-tech-radar.md)选择真实MySQL或匿名GitHub GET。Java测试在标准`trend/`目录，密钥包装以[安全说明](deepseek-key-safety.md)中的假后端脚本验证，不传真实Key。

## 自动化浏览器访问本机服务

开发终端显示 Vite `ready`，只说明进程完成启动。浏览器连接超时时，先在实际运行浏览器的宿主机终端检查监听和 HTTP 响应，避免把不同隔离环境的结果混用：

```powershell
Get-NetTCPConnection -LocalPort 5173 -State Listen
curl.exe --noproxy '*' --max-time 8 -o NUL -w '%{http_code}' http://127.0.0.1:5173/polyethene-polyhouse/
```

本轮 Windows 验收中，受限沙箱启动的 Vite 在宿主机可见监听，但宿主机 HTTP 请求和内置浏览器都无法连接。停止那一次验收进程，改在宿主机终端进入隔离副本的 `polyhouse-web/`，执行 `npm run dev -- --host 127.0.0.1 --strictPort` 后，同端口请求返回 200，内置浏览器也正常加载。工具启动时按其权限流程允许这次本机服务运行；这是运行环境边界处理，无需修改 Vite 配置、切换浏览器或关闭防火墙。详细实测见[dev.14](../dev-log/v1.0-dev.14.md#f1-浏览器验收收口2026-11-14)。

若端口属于其他任务，先识别进程归属，再给本轮验收选空闲端口。后端也应在能连到本机 MySQL/Redis、且前端代理可达的环境中启动。结束后停止本轮进程并清理本轮隔离资源。
