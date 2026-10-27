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
# 编译并执行全部本机 MySQL、Redis、认证事务及真实运行密钥验收。
.\polyhouse\scripts\Invoke-LocalAuth.ps1 -Action Verify
# 使用同一预设启动后端。
.\polyhouse\scripts\Invoke-LocalAuth.ps1 -Action Run
```

入口只读取允许的环境变量名，导入当前进程后调用 Maven，并在成功或失败退出时恢复原环境；不修改系统环境，不执行 XML 内容。实例验收限制为本机 `polyhouse_db` 和本机 Redis，避免误继承外部连接。IDEA 自身启动应用时直接使用同一份个人配置。

新机器缺少预设时，先在 IDEA 建立 `PolyhouseApplication` 运行配置，再执行 `.\polyhouse\scripts\Initialize-LocalAuth.ps1`，通过隐藏输入提供数据库密码。初始化只在密钥缺失时生成一次 32 字节安全随机密钥；重复执行保留已有账号、密码和密钥，无效旧密钥会明确拒绝而非静默替换。测试隔离用例仍可使用临时随机密钥，专门的运行配置用例会验证这里保存的固定密钥。

## 公共层响应约定

成功响应为 HTTP 200，JSON 字段固定为 `code`、`message`、`data`，成功业务码为 `0`。失败响应保留相同 JSON 结构，`data` 为 `null`。

业务失败通过 `BusinessException(ErrorCode)` 声明。`ErrorCode` 集中定义业务码、默认文案和 HTTP 状态，例如参数错误为 400、未登录为 401、权限不足为 403、资源不存在为 404、冲突为 409、未知异常为 500。

框架产生的 HTTP 错误保留原状态和必要响应头，例如 405 的 `Allow`。错误体固定为 `application/json`，即使客户端请求了不支持的响应格式，也能收到 406 及统一错误体。请求输入、原始异常消息和异常链不进入错误响应；未知异常日志只记录异常类型及应用代码位置。

MyBatis 的 SQL 和绑定参数日志默认关闭。认证拦截器在请求开始、认证失败、正常/异常完成及异步切换时清理 `UserContext`；线程变量不会自动传递到异步任务。

## 验证

使用 JDK 21，在 `polyhouse/` 中执行：

```powershell
mvn -q compile -DskipTests
mvn -q test
```

在 `polyhouse-web/` 中执行：

```powershell
npm run build
node --test tests/auth-session.test.mjs tests/growth.test.mjs
```

编译通过和 Spring 上下文启动成功不等于数据库或 Redis 已完成连接验证；实际数据读写需要另行集成测试。普通测试不会执行 MySQL 查询，MySQL 和 Redis 实例测试默认跳过。

## 前端启动与账号交互

先使用本机预设启动后端，再在 `polyhouse-web/` 执行 `npm run dev`，访问 `http://localhost:5173/`。沿用现有 Vite `/api` 代理到本机 8080；前端不需要也不应配置数据库密码或 JWT 签名密钥。页面路由使用浏览器历史模式，正式静态托管仍需将未知页面路径回退到 `index.html`。

右上角打开登录/注册弹窗；注册成功后切回登录，未自动登录。登录使用 PC 会话，账号及密码原样提交，前端同时检查 72 字节的 UTF-8 密码限制。提交过程中禁用重复操作，密码在提交结束、切换模式及关闭后清除，不保存到 localStorage。

刷新或回到页面时，请求 `/api/auth/me` 确认最新身份；`polyhouse_user` 仅为既有缓存格式，不从其恢复昵称、角色或权限。确认前显示恢复状态；网络/服务故障、403 或响应格式异常不清除 Token，也不显示未确认身份，提供重试。认证请求的 401 清除匹配的旧会话，公开登录的错误密码不触发全局清理。其他标签页登录/退出会同步并重新核验，旧请求不能覆盖新会话。

浏览器禁止保存或缓存写入失败时，仍可使用已确认的内存身份，并提示刷新后可能需要重新登录。右上角个人卡片展示真实昵称、身份、等级、累计经验与硬币，并提供个人中心和退出。`/account` 展示只读资料、成长进度、下级门槛、满级状态、每日奖励状态、工具入口及账号管理；身份未确认时不展示资料。头像采用昵称首字，头像上传和资料修改尚未开放。请求错误只向界面传播固定提示，不附带包含凭据的 Axios 对象。

个人中心的注销弹窗必须勾选影响说明，并准确输入“注销账号”；取消或确认期间切换账号后，旧确认失效。注销会停用账号并撤销全部终端会话，原账号名不能复用，已有资料和历史记录仍保留，不代表永久删除个人数据。前端成功反馈只在服务端确认后显示；网络/服务故障不能冒充操作成功，也不能据此断言服务端未执行。退出与注销共用命令占用状态，防止跨入口重复提交。

## 用户模型与本地 MySQL

`User` 映射现有 `sys_user` 表，角色使用 `UserRoleEnum` 的语义字符串，`ext_json` 映射为 JSON 对象，查询自动排除逻辑删除账号。创建、更新时间由 MySQL 维护，实体写入不会覆盖它们。

`RegisterDTO` 仅接收 `username/password/nickname`，`LoginDTO` 接收 `username/password` 和可选 `clientType`（PC/H5/PLUGIN，省略时默认 PC）。账号约定为 4 至 32 位字母、数字或下划线；昵称非空白且不超过 32 个字符；密码至少 8 个字符，UTF-8 编码最多 72 字节，以适配策划书指定的 BCrypt，不能简单按 72 个字符处理中文密码。不裁剪或归一化密码。DTO 的 JSON 输出和默认文本输出均隐藏密码，Service 与 Controller 的 `@Valid` 均执行边界校验。

`UserProfileVO.from(user)` 仅保留 `id/username/nickname/avatar/role/experience/level/coins`，不返回密码哈希、状态、登录 IP、软删除标记或任意扩展 JSON。雪花 ID、累计经验和整数硬币余额以十进制字符串传给前端，避免 JavaScript 数字精度丢失；等级为整数。注册、登录、当前用户 HTTP 接口均复用这些安全模型。

初始化基线位于 [polyhouse/sql/001_init.sql](polyhouse/sql/001_init.sql)，按只读策划书归档四张表的结构，不自动执行。现有数据库无需重新初始化；该文件仅供空环境人工建表，不能当作迁移脚本反复执行。账号唯一索引与软删除并存，因此删除后账号仍占用，后续注册不得默认允许复用。

成长字段由追加迁移 [polyhouse/sql/002_user_growth.sql](polyhouse/sql/002_user_growth.sql) 提供，奖励流水由 [polyhouse/sql/003_daily_growth_rewards.sql](polyhouse/sql/003_daily_growth_rewards.sql) 提供。空环境依次执行 `001`、`002`、`003`；已完成 `002` 的数据库只追加 `003`，必须在运行新版应用或实例测试之前完成，不由应用启动自动改表。使用已有个人配置连接本机 `polyhouse_db` 后，可在 MySQL 客户端执行所需迁移（示例为当前仓库路径，不包含凭据）：

```sql
source E:/project/java/polyethene-polyhouse/polyhouse/sql/002_user_growth.sql
source E:/project/java/polyethene-polyhouse/polyhouse/sql/003_daily_growth_rewards.sql
```

迁移为 `sys_user` 追加 `experience bigint NOT NULL DEFAULT 0`、`level int NOT NULL DEFAULT 1`、`coins bigint NOT NULL DEFAULT 0`，包括非负经验/余额和至少 1 级的数据库约束，旧账号也自动取得初值。每列存在时跳过，重复执行不会清零余额；不会修正其他环境中已存在但定义不一致的同名列，升级时仍需核对结构。MySQL DDL 隐式提交，不能假定失败时可整批事务回滚；迁移支持中断后重跑。

三项由**独立成长服务**管理：`UserGrowthService.rewardDailyLogin(userId)` 是受信任业务唯一的每日结算入口，`UserGrowthRules` 集中管理奖励和等级曲线，`UserGrowthConfig` 提供服务端时钟，`UserGrowthMapper` 记录流水。Controller 不接受奖励数值，不新增直接加经验/加币的公开接口。注册/登录 DTO 的额外成长字段不生效；通用实体插入使用数据库初值，通用实体更新跳过这些字段，避免旧资料覆盖余额。等级与 `USER/INSIDER/OWNER` 权限独立，不进入 JWT；前端已接入真实数值及成长状态展示。

### 每日登录奖励与升级

每日首次成功登录，或使用有效 Token 访问已认证 API/恢复 `/api/auth/me`，自动获得 **10 经验 + 1 枚硬币**；退出和软注销本身不发奖。按服务端 `Asia/Shanghai` 自然日计算，同账号的 PC/H5/PLUGIN、重复登录、刷新和并发请求共享每日一次机会。注册本身不发奖；错误密码、无效/过期/已撤销 Token、禁用或软注销账号不发奖。没有登录的日期不补发，中断不清零、不降级，365 个有奖励的自然日达到六级。认证检查发生在角色拦截前，所以当天已核验身份但业务权限不足的访问也可计作一次有效登录。

| 等级 | 累计经验门槛 | 仅靠每日登录达到的累计天数 |
| --- | --- | --- |
| Lv1 | 0 | 注册初始 |
| Lv2 | 30 | 3 |
| Lv3 | 200 | 20 |
| Lv4 | 580 | 58 |
| Lv5 | 1370 | 137 |
| Lv6 | 3650 | 365 |

曲线参照 B 站 0/200/1500/4500/10800/28800 的累计门槛比例，缩放到 365 天后按天向上取整，再乘每日 10 经验；这是聚妙屋自己的规则。升级不扣经验，Lv6 之后仍继续累计经验和每日硬币，等级保持 6；硬币用途尚未开放。

结算先锁定 `sys_user` 的对应行并核对最新状态，再以当前读检查 `sys_user_growth_reward`，只有尚未领取才在同一 MySQL 事务中插入流水、增加经验/硬币并计算等级。主键 `(user_id, action_code, reward_date)` 在数据库兜底防重，规则版本和终端类型不参与防重主键；不用 Redis 的过期键代替奖励账本。登录中的结算加入原登录事务，Redis 会话发布失败时审计、奖励和余额一起回滚；Redis 本身仍不参与 MySQL 事务。

流水保留行为、结算日期、经验/硬币增量与规则版本。软注销保留历史；用户物理删除时由外键级联清理其所属流水，实例测试只物理清理自己创建的随机用户。数值溢出或存储失败明确返回 503 并回滚，不静默丢奖；奖励依赖故障也会阻止当次认证完成，不把部分成功展示为正常登录。

当前只启用服务端可验证的每日登录行为。其他行为奖励及其次数上限、硬币消费待后续需求；工具在浏览器本地运行，不应仅凭浏览器自报“完成一次处理”发奖。未来增加行为时须通过受控成长服务定义验证方式、限次策略与流水，不直接修改资料字段。

### 成长展示与当日到账状态

`GET /api/auth/growth` 只接受当前 Bearer 身份，返回 `UserGrowthVO`：累计经验/硬币、当前等级/上限、当前等级起点、下级累计门槛、还需经验、等级内整数百分比，以及奖励日/时区、当日到账标志、经验/硬币增量、服务端观测时间和下次零点。编号、累计值和门槛使用十进制字符串；满级的下级门槛为 `null`、还需经验为 `"0"`、进度为 100，累计经验和硬币继续保留。

`UserGrowthService.getStatus` 按既有“用户 → 流水”顺序锁定读取，本身不写余额或新增奖励；HTTP 的统一认证仍会结算有效登录。已到账标志及数额读取当日真实流水，即使以后奖励规则改变，也不会把旧流水重新解释为新数额；尚未到账只显示当前规则的“预计”值。现有资料响应保持兼容，无新增数据库迁移或公开领取接口。

个人中心与头像小窗共用 `useUserGrowthStore` 的内存快照，不把到账标志写入本地缓存，不重复弹领取成功消息。相同身份并发查询去重，退出/换号后旧响应失效；跨日间隔使用两个服务端时刻的差值，避免客户端日期错误。零点使旧快照失效，隐藏页面不主动发送活跃请求，重新可见时先核验身份再刷新。网络失败提供重试，不能据此宣称奖励到账。

原四张表保持策划书基线，用户表追加成长字段，并另建每日奖励流水表；原有表排序规则为 `utf8mb4_0900_ai_ci`，账号判重不区分大小写，本轮保留现状。未来变更字符比较规则必须先评估已有账号，不能直接改表。

在 Maven 所在的同一个进程环境提供 `DB_USERNAME/DB_PASSWORD`（需要时也提供 `DB_URL`），然后在 `polyhouse/` 执行：

```powershell
mvn -q '-Dpolyhouse.mysql.integration=true' '-Dtest=UserMapperIntegrationTest' test
```

测试核对表结构、三种角色和嵌套 JSON 往返、默认值、数据库审计时间、账号唯一约束及逻辑删除。测试只插入随机命名的账号，全部写入包在默认回滚的测试事务中；不重建表、不扫描或删除真实用户。仅对专用本地开发数据库启用，禁止配置到生产库。

同时验证全部测试、MySQL 与 Redis 时执行：

```powershell
mvn -q '-Dpolyhouse.mysql.integration=true' '-Dpolyhouse.redis.integration=true' '-Dpolyhouse.auth.integration=true' test
```

## JWT 基础工具

当前六项认证 HTTP 接口和认证/角色拦截器均已接入。签名校验通过不等于账号仍启用、角色仍有效或会话尚未退出，Service 会继续核对 Redis 和数据库。

`JwtUtils` 保持静态且不持有业务状态：`keyFromBase64` 校验外部密钥，`createToken` 签发访问令牌，`parseAndValidate` 验签后返回内部 `TokenClaims`。密钥、当前时间和有效期由调用方传入，工具不读取环境、不注入 Bean、不访问 MySQL/Redis，也不自动裁剪 `Bearer` 前缀。

仅允许 HS256，固定签发方 `polyhouse`、接收方 `polyhouse-api` 和用途 `ACCESS`。载荷仅包含用户 ID、语义角色、终端类型、随机 `jti` 及签发/过期时间等必要声明；不携带密码、密码哈希、手机号或任意用户资料。JWT 载荷可以解码读取，签名提供防篡改校验，不提供内容保密。

终端类型为 `PC/H5/PLUGIN`，签发时未指定则默认 PC。用户 ID 以字符串 subject 保存；随机 `jti` 保证同一用户同一秒内重复签发的令牌仍可区分。验证会拒绝错误签名、非 HS256 算法、错误归属/用途、缺失或无效身份、压缩载荷、未识别的关键头、未来签发及过期令牌；到达 `exp` 的同一时刻即失效。无效令牌统一抛出不携带原 Token 或异常链的安全异常，由 HTTP 认证入口转成 401。

`JwtProperties` 从 `polyhouse.jwt` 读取配置。有效期唯一来源是 `application.yml` 的 `polyhouse.jwt.expiration-days`，当前为七天；已移除 Redis 中重复的七天常量。白名单使用签发令牌的 `expiresAt` 减去当时时间作为剩余 TTL，避免保存延迟或重新写入延长会话。

密钥必须来自运行环境，禁止使用账号密码、示例短字符串或每次启动临时生成的替代密钥。当前其他模块允许在密钥为空时启动，但调用 `JwtProperties.signingKey()` 时会明确拒绝缺失、错误编码或长度不合格的配置。配置为空不代表 JWT 已能在本机实际使用。

日常开发优先复用上面的本机预设，不在每次启动或会话中换密钥。以下代码仅用于有意隔离的临时实验，它只设置进程环境，不打印密钥、不写系统环境或项目文件：

```powershell
$jwtBytes = New-Object byte[] 32
$jwtRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $jwtRandom.GetBytes($jwtBytes)
    $env:POLYHOUSE_JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
} finally {
    $jwtRandom.Dispose()
    [Array]::Clear($jwtBytes, 0, $jwtBytes.Length)
}
```

该变量只对当前终端及其子进程生效，重开终端后需另行配置。需要跨重启保留会话时，应通过个人受保护的本地配置持久保管同一密钥；IDEA 使用它自身运行配置中的环境变量。更换密钥会使旧令牌验签失败，不把上面的生成代码加入应用启动流程。

JWT 专项测试使用临时随机测试密钥和固定时间，不要求真实密钥或外部服务：

```powershell
mvn -q '-Dtest=JwtUtilsTest,JwtPropertiesTest' test
```

## 注册登录 Service

`AuthService` 提供 `register/login/getCurrentUser/getGrowthStatus/logout/deregister` 业务方法，六项均已有 HTTP 入口。已映射的 `/api/**` 业务请求默认要求有效 Bearer 身份；注册、登录及独立核验请求体 Token 的撤销入口显式跳过统一 Bearer 检查。注册返回安全用户资料，不自动登录；登录返回 `LoginVO` 的 `accessToken/tokenType/expiresAt/user`。VO 的默认文本表示隐藏令牌，正常 JSON 响应按需交付令牌。

### HTTP 入口与权限行为

成功响应均为 `Result<T>` 的 `code/message/data`，命令型接口的 `data` 为 `null`。

| 方法与路径 | 输入 | 成功数据 |
| --- | --- | --- |
| `POST /api/auth/register` | JSON：`username/password/nickname`，额外角色字段不生效，注册固定 USER | `UserProfileVO` |
| `POST /api/auth/login` | JSON：`username/password/clientType`，终端可省略 | `LoginVO`，包含 Token、到期时间和安全资料 |
| `GET /api/auth/me` | 唯一的 `Authorization: Bearer <JWT>` 请求头 | 最新 `UserProfileVO` |
| `GET /api/auth/growth` | 唯一的 `Authorization: Bearer <JWT>` 请求头，无领取参数 | 当前账号的 `UserGrowthVO` |
| `POST /api/auth/logout` | 保留原 JSON：`token`，值不含 Bearer 前缀 | `null` |
| `POST /api/auth/deregister` | 保留原 JSON：`token`，值不含 Bearer 前缀 | `null` |

`/me` 不接受查询参数中的 Token；缺失、重复、错误格式的 Bearer 头为 401。`ClientRequestDTO` 与请求头 Token 的 DTO 由专用解析器构造、校验，Controller 不接收 Entity 或可伪造的用户 ID。登录审计地址来自 Servlet 连接对端，忽略同名查询参数与 `X-Forwarded-For`；可信反向代理的真实地址处理尚未配置。

`AuthInterceptor` 复用已有 `getCurrentUser` 验证签名、当前 jti 和数据库最新角色，再写入 `UserContext`。方法与类上的 `@RequireRole` 必须同时满足，低方法要求不能降低类级门槛；`@SkipBearerAuth` 也不能覆盖角色要求。未登录或失效会话返回 401，已登录而权限不足返回 403，依赖故障返回 503，不把缓存故障当作有效身份。

当前角色受限探针只存在于测试源码，不提供生产管理或 AI 接口。请求失败、异常和异步切换均清理上下文；异步业务仍须显式传递身份，不能假定 ThreadLocal 会跨线程传播。

跨域来源允许本机前端 `http://localhost:5173`、`http://127.0.0.1:5173` 和策划书预留的 `chrome-extension://*`，仅开放 GET/POST/OPTIONS，不携带 Cookie 凭据。预检先于身份认证；来源许可不授予登录或角色权限，拒绝来源/方法仍返回统一 JSON 403。部署到其他域名时需明确配置来源，当前不开放任意网站。

新注册账号在业务层按 `Locale.ROOT` 转小写，查找和判重使用同一规范，匹配本机数据库不区分大小写的唯一索引。既有账号不批量改名；密码保持原样。注册只创建 USER，业务预查和唯一索引共同处理并发或软删除账号冲突，密码只保存 BCrypt 哈希。

按用户最新决定，登录失败只返回统一的“账号或密码错误”，允许立即重新尝试，不累计失败次数、不锁定账号、不返回十五分钟等待提示。不存在账号仍执行等成本的虚拟 BCrypt 验证；禁用或已删除账号仍不能登录。失败尝试不会清除既有有效会话。此决定覆盖只读策划书原有的五次失败锁定方案，策划书文件本身保持不变。

Redis 白名单键仍为 `polyhouse:auth:token:{userId}:{clientType}`，值仅保存随机 jti，不保存完整 Bearer Token。同端新登录替换旧会话，PC/H5/PLUGIN 互不影响；退出原子比较 jti 后删除，旧令牌或重复退出不会删除新会话。TTL 取实际 `expiresAt` 的剩余时长。

`getCurrentUser` 检查签名、白名单及数据库账号状态，并使用数据库最新角色，避免旧 Token 的 OWNER 快照在账号降权后继续授予权限。缺失密钥、数据库或 Redis 故障均明确拒绝认证，不退化为跳过检查。

登录只定向更新审计字段，并复核状态及密码哈希；审计 datetime 按现有 `Asia/Shanghai` 约定记录，JWT 时间仍为 UTC。登录现在使用数据库事务，让审计更新的用户行锁保持到 Redis 会话发布后，与软注销串行执行，防止注销清理后又出现并发登录写入。会话存储失败不交付 Token，并回滚审计更新。MySQL 和 Redis 仍不是跨库事务：Redis 操作成功而数据库提交失败时，可能已有会话被撤销或替换，不能据此宣称两种存储可以一起回滚。

保存会话仍使用单条 SET 同时写入 jti 和 TTL；退出仍使用单键 Lua 比较后删除。旧版本留下的失败计数键不再读写，可按原 TTL 自然过期，不扫描或批量清理 Redis。

业务联调使用随机测试账号、数据库回滚事务、精确 Redis 键清理和进程内临时 JWT 密钥。需要本机 MySQL、Redis 在线及 Maven 环境中的数据库凭据，但不要求用户先配置日常 JWT 密钥：

```powershell
mvn -q '-Dpolyhouse.auth.integration=true' '-Dtest=AuthServiceIntegrationTest' test
```

仅验证 Redis 原子业务操作时执行：

```powershell
mvn -q '-Dpolyhouse.redis.integration=true' '-Dtest=AuthRedisStoreIntegrationTest' test
```

本机固定运行密钥已单独配置并验收；上述业务测试不替代完整浏览器登录验收，相关边界见当前阶段日志。真实 HTTP 联调会用随机端口启动服务器，并连接本机 MySQL/Redis，使用本机预设的固定运行密钥：

```powershell
.\polyhouse\scripts\Invoke-LocalAuth.ps1 -Action Verify
```

完整入口自动启用 `AuthHttpIntegrationTest`，验证注册/重复账号、连续失败立即重试、当前资料、过期/篡改/未登记令牌、数据库角色变更与禁用、多端退出和软注销。HTTP 账号用 `http_it_` 随机命名，结束时只清理本次登记的精确记录和会话；不开启请求/响应全文打印，也不把密码或 JWT 写进测试诊断。

完整入口也启用 `UserGrowthIntegrationTest`：用可控奖励时钟和真实存储验证 365 天升级、满级后累计、跨上海零点、跨端防重、漏登不补发、8 事务竞争、外层旧快照、数据库主键防重、失败回滚重试和数值溢出。随机账号为 `growth_it_`，只清理本次登记的记录及其级联流水；奖励时钟不改变 JWT 时间或系统时钟。`AuthTransactionIntegrationTest` 同时验证会话发布失败时成长奖励随登录一起回滚。

### 软注销、回滚与真实并发验收

在本机开发数据库和 Redis 在线、当前 Maven 进程已提供数据库环境变量时执行：

```powershell
mvn -q '-Dpolyhouse.auth.integration=true' '-Dtest=AuthServiceIntegrationTest,AuthTransactionIntegrationTest' test
```

`AuthTransactionIntegrationTest` 不使用测试类外层回滚事务，而让服务实际提交或回滚，验证软删除标记、全部终端撤销、其他账号不受影响，以及 Redis 故障前后不同的回滚边界。两种登录/注销竞争顺序各重复三次，通过门闩控制执行位置，并读取 `performance_schema.data_lock_waits` 证明竞争线程确实等待了用户行锁，不以固定睡眠代替并发证据。

本测试需要 MySQL 8 的实时锁观察表读取权限，仅限本机开发环境。随机账号为 `auth_tx_` 前缀，测试登记精确 ID 和账号后才允许清理；已提交的测试记录与三类会话在结束时清除，失败也尝试清理，不扫描或删除业务用户。原 `AuthServiceIntegrationTest` 继续使用外层回滚事务。

故障模拟只拦截测试上下文中的 Redis 调用，不停止 Redis 服务。DEL 执行前失败时，账号回滚且原会话保留；DEL 执行后确认失败时，账号仍回滚，但 Redis 会话不会自动恢复，用户需要重新登录。测试随机密钥不等于日常运行密钥。

运行密钥另用真实配置核对，不覆盖为临时测试密钥：先在当前 Maven 进程提供 `POLYHOUSE_JWT_SECRET`，再执行：

```powershell
mvn -q '-Dpolyhouse.jwt.runtime=true' '-Dtest=JwtRuntimeConfigurationIntegrationTest' test
```

该用例读取实际 `application.yml` 及进程环境，在内存签发、验签并核对配置有效期，不输出密钥或 Token，也不写入数据库或 Redis。IDEA 个人运行配置的变量只传给 IDEA 启动的应用，不会自动传给其他终端；根 `.env` 仍不自动加载。

### 退出登录与注销账号的范围

两项接口都使用 `Content-Type: application/json`，请求体为 `{"token":"当前 JWT"}`，不带 `Bearer ` 前缀。凭据通过带校验注解的 `SessionTokenDTO` 接收，不从 URL、用户 ID 或未验证的本地资料决定操作对象。成功响应为 `{"code":0,"message":"操作成功","data":null}`。

| 接口 | 实际行为 |
| --- | --- |
| `POST /api/auth/logout` | 校验签名后原子比较 jti 并撤销当前终端会话；重复退出或旧 Token 退出不会误删新会话，其他终端保留。 |
| `POST /api/auth/deregister` | 要求当前仍在白名单中的有效会话，锁定对应用户后经 MyBatis-Plus 逻辑删除置 `deleted=1`，单条 DEL 清除 PC/H5/PLUGIN 会话；清理失败返回 503 并回滚数据库删除。 |

空白、缺失或超过 4096 字符的 Token 请求返回 400；签名无效、过期，以及注销时会话已被替换或账号失效，返回 401；数据库/Redis 故障返回安全错误，不返回底层异常或凭据。退出允许已被替换但签名仍有效的旧 Token 幂等撤销自己的会话。

前端 `api/auth.ts` 和 `useUserStore.logout()/deregister()` 已可调用：成功后同步清除内存状态与 `polyhouse_token/polyhouse_user`；401 清理失效会话但仍报告操作失败；网络或服务故障保留状态供重试。等待请求期间取得了新 Token 或新会话版本时，旧响应不会清掉新会话，也不删除另一标签页新写入的缓存。登录/注册弹窗、刷新恢复、个人卡片/个人中心、退出反馈及注销确认均已接入；注销始终只针对当前已核实身份，页面不提交用户 ID 或任意用户名来指定对象。

本次实现的是软注销：账号不能再登录，用户名继续被唯一索引占用；未物理删除或匿名化个人资料、云端模板和个人预设，也未新增恢复期、二次密码验证或 OWNER 移交策略。上述能力应单独确定规则后实施，不将软删除宣称为个人数据永久清除。当前新增 MySQL 软注销联调用例默认按开关跳过，需配置本机数据库凭据后使用上面的认证业务联调命令执行。

## 本地 Redis

当前开发环境使用 Ubuntu WSL2 内的 Redis 单机服务，Windows 后端通过 `127.0.0.1:6379` 访问逻辑数据库 `0`。实例无认证、仅监听本机地址；这些设置只适用于当前本地环境。项目不另外启动 Docker Redis，也不更改 WSL 配置文件或 Redis 服务配置。

在 Windows PowerShell 中检查服务：

```powershell
wsl -d Ubuntu -- systemctl is-active redis-server
wsl -d Ubuntu -- systemctl is-enabled redis-server
wsl -d Ubuntu -- redis-cli -h 127.0.0.1 -p 6379 PING
```

当前预期输出分别为 `active`、`enabled` 和 `PONG`。`enabled` 表示随 Ubuntu 启动，不保证 Windows 登录时必定启动 Ubuntu；开发前执行上述命令即可唤起发行版并验证服务。若出现 localhost 代理提示但随后返回 `PONG`，说明本机 Redis 检查成功，该代理提示不影响这次连接。

`pom.xml` 已包含 `spring-boot-starter-data-redis`，由 Spring Boot 管理 Lettuce 的版本和连接工厂。`RedisConfig` 将常规模板的键、值和 Hash 字段统一为 UTF-8 字符串；业务代码也可以直接注入自动配置的 `StringRedisTemplate`。存储结构化数据时应显式转换为 JSON，不使用 Java 原生对象序列化。

### Spring 连接验证

在 `polyhouse/` 目录执行：

```powershell
mvn -q '-Dpolyhouse.redis.integration=true' '-Dtest=RedisConnectionIntegrationTest' test
```

这会读取实际 `application.yml` 及环境变量，验证 PING、中文文本读写、计数器递增和 TTL。每次写入使用随机的 `polyhouse:dev:integration:` 前缀键，首次创建就设置 60 秒过期，测试后只清理自己创建的键，不扫描或清空业务数据。测试只加载 Redis 配置，不依赖 MySQL。

需要运行普通回归并启用 Redis 实例测试时执行（MySQL 和认证业务实例仍按各自开关启用）：

```powershell
mvn -q '-Dpolyhouse.redis.integration=true' test
```

### IntelliJ IDEA 连接

1. 打开 `View → Tool Windows → Database`。如果没有该窗口，在 `Settings → Plugins → Installed` 检查 `Database Tools and SQL`；相关功能受 IDEA 版本和 Ultimate 授权影响。
2. 在 Database 窗口点击 `+ → Data Source → Redis`，命名为 `Polyhouse Local Redis`。
3. Connection type 选择 `standalone`，Host 填 `127.0.0.1`，Port 填 `6379`，Database 填 `0`。
4. Authentication 选择 `No auth`，用户名和密码留空，不启用 SSH 或 SSL。以后服务器启用认证时，再同时更新 IDEA 和后端环境变量。
5. 若提示 `Download missing driver files`，点击下载。IDEA 的驱动与 Maven 的 Lettuce 是两个独立客户端，下载 IDEA 驱动不需要改 `pom.xml`。
6. 点击 `Test Connection`，成功后点击 `Apply/OK`。IDEA 连接与后端连接彼此独立，后端成功不代表 IDEA 驱动已经配置。
7. 在数据源控制台执行 `PING`，预期得到 `PONG`。如树中看不到数据库 `0`，在数据源的 `Schemas` 页勾选；测试键会自动清理，因此看不到测试键是正常现象。

这些操作参考 [JetBrains Redis 连接说明](https://www.jetbrains.com/help/idea/redis.html)。IDEA 数据源设置属于个人环境，已由根 `.gitignore` 的 `.idea/` 规则排除。
