# 聚妙屋 Polyhouse

后端位于 `polyhouse/`，使用 JDK 21、Spring Boot 3 和 MyBatis-Plus；前端位于 `polyhouse-web/`，使用 Vue 3、TypeScript 和 Vite。

修改项目之前必须完整阅读根目录 `AGENTS.md`。策划书为只读文件 `Polyhouse_Ultimate_Master_Blueprint.docx`。

当前处于 **v1.0 开发中，尚未发布**。当前状态、近期验证和后续顺序见 [DEV_LOG.md](DEV_LOG.md)，详细进展见其链接的当前阶段日志，历史记录按需查阅 [docs/dev-log/](docs/dev-log/)；本文件负责说明配置、运行和验证，不将文件骨架视为已完成业务功能。

## 本地配置

根目录 `.env.example` 仅列出环境变量名称和非敏感示例。Spring Boot 当前不会自动读取根目录 `.env` 或 `.env.example`；复制文件、在文件中填值都不会自动把变量传入后端。

推荐在 IntelliJ IDEA 的后端运行配置中，通过 `Run → Edit Configurations → Environment variables` 设置所需变量。配置保留在个人本地，不共享包含真实凭据的运行配置。

| 环境变量 | 用途 | 当前默认值 |
| --- | --- | --- |
| `DB_URL` | JDBC 连接地址 | 本机 `polyhouse_db` |
| `DB_USERNAME` | 数据库用户名 | `root` |
| `DB_PASSWORD` | 数据库密码 | 空，需要按实际环境填写 |
| `REDIS_HOST` | Redis 主机 | `127.0.0.1` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_DATABASE` | Redis 逻辑数据库编号 | `0` |
| `REDIS_USERNAME` | 启用 Redis ACL 时的用户名 | 空，当前本地实例无认证 |
| `REDIS_PASSWORD` | Redis 认证密码 | 空，按实际服务配置填写 |
| `REDIS_CONNECT_TIMEOUT` | 建立 Redis 连接的超时 | `3s` |
| `REDIS_TIMEOUT` | Redis 命令超时 | `5s` |
| `POLYHOUSE_JWT_SECRET` | JWT 的 HS256 签名密钥，32 字节安全随机数据的标准 Base64 编码 | 配置默认空；本机固定值由私有预设提供 |
| `POLYHOUSE_AVATAR_DIRECTORY` | 独立的头像持久目录，需有读写权限 | 默认空时上传不可用；本机预设为 `E:\polyhouse-data\avatars` |

也可以在启动 Maven 的同一个 PowerShell 会话中设置环境变量。以下仅展示非敏感连接参数的设置方式：

```powershell
$env:DB_USERNAME = 'root'
Set-Location E:\project\java\polyethene-polyhouse\polyhouse
mvn spring-boot:run
```

真实密码或密钥应在本地运行配置中填写，不复制进 Git 跟踪的文件，也不在终端输出。已打开的 IDE 不会继承另一个终端后来设置的变量。

### 本机认证预设（后续会话优先复用）

本机数据库凭据和固定 JWT 运行密钥保存在 Git 忽略的 `polyhouse/.idea/workspace.xml`，对应 `PolyhouseApplication` 个人运行配置。后续开发和验收先检查并复用这个预设，不重复询问或复制凭据；仅在配置缺失、失效或用户要求更换时重新配置。Git 忽略不等于加密，个人文件不能共享或强制加入版本库。

在仓库根目录执行：

```powershell
# 只输出是否配置、密钥格式和 Git 忽略状态，不输出实际值。
.\polyhouse\scripts\Invoke-LocalAuth.ps1 -Action Inspect
# P1 账号安全验收：随机独立测试库，不在开发库创建 OWNER。
.\polyhouse\scripts\Invoke-LocalAuth.ps1 -Action Verify -AccountSecurityOnly
# 使用同一预设启动后端，自动保持 Ubuntu WSL 并等待本机 Redis 就绪。
.\polyhouse\scripts\Invoke-LocalAuth.ps1 -Action Run
```

IDEA 绿色 Run/Debug 按钮需另绑启动前外部工具，步骤见[Redis 启动前配置](docs/learning/backend-basics.md#本地-redis)；这是启动检查，不是运行期监控。

入口只读取允许的环境变量名，导入当前进程后调用 Maven，并在成功或失败退出时恢复原环境；不修改系统环境，不执行 XML 内容。实例验收限制为本机 `polyhouse_db` 和本机 Redis，避免误继承外部连接。IDEA 自身启动应用时直接使用同一份个人配置。

新机器缺少预设时，先在 IDEA 建立 `PolyhouseApplication` 运行配置，再执行 `.\polyhouse\scripts\Initialize-LocalAuth.ps1`，通过隐藏输入提供数据库密码。初始化只在密钥缺失时生成一次 32 字节安全随机密钥；重复执行保留已有账号、密码和密钥，无效旧密钥会明确拒绝而非静默替换。测试隔离用例仍可使用临时随机密钥，专门的运行配置用例会验证这里保存的固定密钥。

当前后端需先执行 001～004 后的追加迁移 [005_account_security.sql](polyhouse/sql/005_account_security.sql)，再启动新版应用；迁移前备份，不在启动时自动改表。本人改密接口、首次限制、凭据版本、隔离验收及回退边界见[账号安全说明](docs/operations/account-security.md)。本机 005 已执行，既有账号密码/角色保持不变；P2 已关闭公共注册并交付屋主开户，先执行 006 再运行新版；入口、接口、一次凭据与回退边界见[屋主开户说明](docs/operations/account-provisioning.md)。P3 账号列表、封禁/解封及屋主代重置已交付，需再执行 007；页面 `/manage`、权限/一次交付/隔离验收与回退见[账号管理说明](docs/operations/account-management.md)。P4 昵称/头像审核已完成，需继续执行 008，再重启后端；新上传地址、私有目录、历史核对与回退见[资料审核说明](docs/operations/profile-moderation.md)。

## 公共层响应约定

成功响应为 HTTP 200，JSON 字段固定为 `code`、`message`、`data`，成功业务码为 `0`。失败响应保留相同 JSON 结构，`data` 为 `null`。

业务失败通过 `BusinessException(ErrorCode)` 声明。`ErrorCode` 集中定义业务码、默认文案和 HTTP 状态，例如参数错误为 400、未登录为 401、权限不足为 403、资源不存在为 404、冲突为 409、未知异常为 500。

框架产生的 HTTP 错误保留原状态和必要响应头，例如 405 的 `Allow`。错误体固定为 `application/json`，即使客户端请求了不支持的响应格式，也能收到 406 及统一错误体。请求输入、原始异常消息和异常链不进入错误响应；未知异常日志只记录异常类型及应用代码位置。

MyBatis 的 SQL 和绑定参数日志默认关闭。认证拦截器在请求开始、认证失败、正常/异常完成及异步切换时清理 `UserContext`；线程变量不会自动传递到异步任务。

## 验证

按 [AGENTS.md](AGENTS.md) 的改动影响选择检查：仅文档不运行构建。以下是可用入口，不要求每轮全部运行。已有正式屋主，禁止在开发库执行不带隔离专项的全量 OWNER 测试，不能为测试清空屋主记录。

项目级 UI/UX 开发辅助技能已固定为 v2.15.0，只供开发时按需查询，不属于网页运行依赖。来源、文件清单、停用/恢复与更新方式见[专门记录](docs/operations/ui-ux-skill.md)；从仓库根目录执行 `.\polyhouse\scripts\Check-UiUxSkill.ps1` 可只读检查完整性。安装不会自动改网站或替代 `AGENTS.md`。

项目根目录可执行统一普通验收（先准备 JDK 21、Maven、满足 `package.json` 的 Node 版本，并在 `polyhouse-web/` 安装锁定依赖）：

```powershell
.\polyhouse\scripts\Test-Project.ps1
```

按顺序执行后端编译、普通测试、前端只检查不修复的 ESLint、`npm test`、类型检查及生产构建；任何阶段失败立即停止，并恢复终端工作目录。不读取 IDEA 私有凭据、不自动安装/迁移/授权/部署，也不自动打开真实实例测试。真实 MySQL/Redis 验收只通过明确的隔离专项执行（安全 `-AccountSecurityOnly`、开户 `-AccountProvisioningOnly`、管理 `-AccountManagementOnly`、审核 `-ProfileModerationOnly`，均配合 `-Action Verify`）；默认普通验收中被关闭的实例测试会报告跳过，不能把它当作真实连接验收成功。

测试目录按业务组织，归类约束见 [AGENTS.md](AGENTS.md)：

| 测试根目录 | 当前归类 |
| --- | --- |
| [前端 tests](polyhouse-web/tests/) | `user/`：认证、账号/管理工作台、资料与成长；`replace/`：妙替内核、批处理、词库与草稿 |
| [后端 Java 测试根](polyhouse/src/test/java/com/polyethene/polyhouse/) | `user/`：用户业务，内部沿用 controller/service 等分层；`common/`：公共契约；`system/`：应用启动、Redis 连接与跨模块容量恢复 |

后端三个测试保留生产代码同包位置：`ops/OwnerBootstrapCommandTest` 测试包可见命令解析，`service/AvatarServiceTest` 调用包可见审核方法，`service/impl/AuthServiceImplTest` 使用包可见时钟构造器；不为目录归类扩大接口。校验夹具 `model/dto/ValidationProbeDTO`、`model/vo/ValidationProbeVO` 保留 DTO/VO 契约包，其他夹具随业务归类。

前端 `npm test` 递归发现全部业务目录；在 `polyhouse-web/` 可定向执行 `node --test "tests/user/*.test.mjs"` 或 `node --test "tests/replace/*.test.mjs"`。后端仍按类名选择，例如在 `polyhouse/` 执行 `mvn -q '-Dtest=AuthControllerTest' test`；现有隔离脚本按类名定位，不因业务目录迁移改用未隔离的全量实例测试。重排测试包后须清理旧编译测试类，避免 Maven 把旧、新包都发现为测试。

U4 页面组合验收可显式使用 `Invoke-LocalAuth.ps1 -Action Verify -ProfileModerationOnly -WorkspaceBrowser`；配合独立 5175 Vite 和本机 5190 桥接，由受控浏览器操作当前界面/真实隔离接口，结束后核对数据库事实并清理，步骤与边界见[资料审核的 U4 验收入口](docs/operations/profile-moderation.md#u4-当前页面组合验收受控浏览器)。

有限负载与故障恢复可单独执行 `.\polyhouse\scripts\Test-CapacityRecovery.ps1`：复用 Git 忽略的私有预设，仅选择隔离验收类，需要本机 MySQL 创建临时库权限及 Ubuntu WSL Redis。它创建并清理随机临时库和 `target` 头像目录，只断开测试专用转发连接，不停止数据库/Redis，不运行 OWNER 夹具或修改开发库。普通验收不启用此测试。妙替完整本地流水线基准在 `polyhouse-web/` 执行 `node benchmarks/poly-replace-pipeline.mjs`，结果写入忽略的 `polyhouse/target/polyreplace-pipeline-results.json`；它测量 Node Worker 进程而非浏览器峰值内存。方法、实测和限制见[容量与恢复验收](docs/operations/capacity-recovery-2026-10-28.md)。

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

## 前端启动与账号交互

先使用本机预设启动后端，再在 `polyhouse-web/` 执行 `npm run dev`，访问 `http://localhost:5173/`。沿用现有 Vite `/api` 代理到本机 8080；前端不需要也不应配置数据库密码或 JWT 签名密钥。页面路由使用浏览器历史模式，正式静态托管仍需将未知页面路径回退到 `index.html`。

右上角只打开登录弹窗，公共注册入口已关闭。已确认 OWNER 可从个人菜单进入“屋主工作台”（`/manage`，开户子页 `/manage/accounts`），创建 USER/ADMIN 并一次交付初始凭据；ADMIN 开户再次验证屋主当前密码。登录使用 PC 会话，账号及密码原样提交，前端同时检查 72 字节的 UTF-8 密码限制。提交过程中禁用重复操作，密码在提交结束及关闭后清除，不保存到 localStorage。

刷新或回到页面时，请求 `/api/auth/me` 确认最新身份；`polyhouse_user` 仅为既有缓存格式，不从其恢复昵称、角色或权限。确认前显示恢复状态；网络/服务故障、403 或响应格式异常不清除 Token，也不显示未确认身份，提供重试。认证请求的 401 清除匹配的旧会话，公开登录的错误密码不触发全局清理。其他标签页登录/退出会同步并重新核验，旧请求不能覆盖新会话。

浏览器禁止保存或缓存写入失败时，仍可使用已确认的内存身份，并提示刷新后可能需要重新登录。右上角个人卡片展示真实昵称、身份、等级、累计经验与硬币，并提供个人中心和退出。`/account` 展示资料、成长进度、下级门槛、满级状态、每日奖励状态、工具入口及账号管理；身份未确认时不展示资料。点击个人中心头像可选择 JPEG/PNG 并裁剪提交，审核通过后导航/小窗同步；未上传或加载失败显示昵称首字。昵称同样通过申请审核后发布。请求错误只向界面传播固定提示，不附带包含凭据的 Axios 对象。

头像在浏览器裁剪，服务端只接收不超过 256KiB 的 256×256 JPEG/PNG 并重新编码。新 `POST /api/auth/avatar/submissions` 使用 Bearer 和 multipart file/requestId，只返回申请编号/状态/额度；旧立即发布接口已关闭。头像每天最多提交 5 次，昵称 2 次，新提交替代同类待审，七天有效；成功入库计次，驳回/替代不退款，失败/回滚不计次，同意图重试不多扣。

待审图片在本机 E 盘公开目录的同级 `avatars-pending` 私有目录，本人/OWNER 鉴权读取；通过后才写入 `sys_user.avatar` 的公开地址，图片字节不进 MySQL/Redis。个人中心新增昵称申请和本人状态；屋主 `/manage/reviews` 提供私有预览、通过/驳回、历史内容保留/下架。旧资料不自动算已审、不批量重置真实屋主。完整接口、实际存储位置、008 迁移/备份及兼容回退见[资料审核说明](docs/operations/profile-moderation.md)与[头像存储说明](docs/operations/avatar-storage.md)。

个人中心的注销弹窗必须勾选影响说明，并准确输入“注销账号”；取消或确认期间切换账号后，旧确认失效。注销会停用账号并撤销全部终端会话，原账号名不能复用，已有资料和历史记录仍保留，不代表永久删除个人数据。前端成功反馈只在服务端确认后显示；网络/服务故障不能冒充操作成功，也不能据此断言服务端未执行。退出与注销共用命令占用状态，防止跨入口重复提交。

## 妙替本地文本替换

规则可主动保存为“本机词库”，支持载入、重命名、复制、删除和清除。词库只属于当前浏览器与站点来源，不随账号，退出登录不会清除；清除浏览器数据会丢失。载入会清空旧预览与下载，仍须明确点击“开始替换”。词库保存失败或其他页面已修改时，先按提示重试或重新读取，未保存的词库候选仅保留在本页。导入导出和分享尚未提供。

“自动保存规则草稿”首次默认关闭，主动开启后记住选择，以 400ms 防抖保存规则编辑（含未完成的空字段、重复查找），不保存文件或结果。草稿与词库分别以 `polyhouse:replace:draft:v1`、`polyhouse:replace:library:v1` 存在同源浏览器的 localStorage，均为本机明文、不随账号；sessionStorage 只记录页面定位 ID，不保存规则。刷新/关页后提示从历史明确恢复，覆盖不同非空编辑须确认，不自动运行。每页独立写入，最多 10 份草稿、每份 64 KiB、整库 768 KiB，满额须手动清理；活动页面来源可复制恢复，关闭来源后才能删除。清空本页保留其他历史，关闭自动草稿会清除全部草稿并阻止其他页旧请求继续保存，正式词库与当前编辑保留。写入失败保留编辑并提示重试；站内离开先补存，失败可返回或明确离开。隐藏/正常关页尽力补存，但浏览器崩溃或强制终止可能丢失最后一次输入；清除浏览器数据也会丢失草稿。

访问 `/replace`，选择 1～20 个文本文件或一个文件夹（单个文件最多 2 MiB、合计 5 MiB），设置最多 20 条字面规则。手动为整批选择 UTF-8（默认）或 GBK / GB18030 输入编码；不自动识别，选错编码可能仍能解码但产生乱码，下载前请检查预览。结果统一为 UTF-8，不保留 GBK 原编码；Python、HTML、XML 等文件内的编码声明不会自动修改，使用时需自行检查。只匹配原文，同位置优先最长规则；空替换表示删除。无法解码或检测到 NUL 字节时整批停止；无法保证识别全部二进制文件。可逐文件切换预览，多文件和文件夹下载 ZIP，单个普通文件直接下载。文件夹结果保留所选根目录和文件路径，浏览器无法提供空文件夹；路径冲突时须重命名。原文件不被覆盖；UTF-8 BOM 和换行保留；文件与规则不上传、不进入账号缓存。输出单文件最多 8 MiB、合计 16 MiB；更多编码和云模板尚未开放。

普通文件选择器包含 `.py`；源码按全文处理，注释、字符串及代码中的匹配都会替换，不提供“只改注释”或代码语法校验。文件夹不自动跳过 `.git/.venv` 等目录，仍适用于上述限制内的小型文本目录。清单中的“移除”仅排除本次任务的文件，不删除磁盘原文件；移除会终止在途处理并清空旧预览/下载结果，需要重新开始。失败后可以移除问题文件直接重试，文件夹模式下剩余文件继续保留原相对路径，排除项不进入结果包。

## 按需说明与学习

- [后端基础详解](docs/learning/backend-basics.md)：用户模型、成长、JWT、认证与退出/软注销、MySQL/Redis 和 IDEA 设置。只在相关任务/学习时读取。
- [屋主初始化与隔离要求](docs/operations/owner-bootstrap.md)：已有正式 OWNER，不重放初始化或清空记录。
- [账号安全](docs/operations/account-security.md)、[开户](docs/operations/account-provisioning.md)、[管理](docs/operations/account-management.md)、[资料审核](docs/operations/profile-moderation.md)：以对应追加迁移、接口和回退说明为准。
- [只读策划书索引](docs/design/blueprint-index.md)、[计划索引](docs/plans/README.md)：规划、批准、实现与验收状态分别查证。
