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
| [前端 tests](../../polyhouse-web/tests) | `user/`：认证、账号/管理工作台、资料与成长；`replace/`：妙替内核、批处理、词库与草稿；`target-kb/`：妙压内核、协议、文件与任务生命周期 |
| [后端 Java 测试根](../../polyhouse/src/test/java/com/polyethene/polyhouse) | `user/`：用户业务，内部沿用 controller/service 等分层；`common/`：公共契约；`system/`：应用启动、Redis 连接与跨模块容量恢复 |

后端三个测试保留生产代码同包位置：`ops/OwnerBootstrapCommandTest` 测试包可见命令解析，`service/AvatarServiceTest` 调用包可见审核方法，`service/impl/AuthServiceImplTest` 使用包可见时钟构造器；不为目录归类扩大接口。校验夹具 `model/dto/ValidationProbeDTO`、`model/vo/ValidationProbeVO` 保留 DTO/VO 契约包，其他夹具随业务归类。

前端 `npm test` 递归发现全部业务目录；在 `polyhouse-web/` 可定向执行 `node --test "tests/user/*.test.mjs"` 、`node --test "tests/replace/*.test.mjs"` 或 `node --test "tests/target-kb/*.test.mjs"`。后端仍按类名选择，例如在 `polyhouse/` 执行 `mvn -q '-Dtest=AuthControllerTest' test`；现有隔离脚本按类名定位，不因业务目录迁移改用未隔离的全量实例测试。重排测试包后须清理旧编译测试类，避免 Maven 把旧、新包都发现为测试。

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
