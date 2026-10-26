# 聚妙屋 Polyhouse

后端位于 `polyhouse/`，使用 JDK 21、Spring Boot 3 和 MyBatis-Plus；前端位于 `polyhouse-web/`，使用 Vue 3、TypeScript 和 Vite。

修改项目之前必须完整阅读根目录 `AGENTS.md`。策划书为只读文件 `Polyhouse_Ultimate_Master_Blueprint.docx`。

当前处于 **v1.0 开发中，尚未发布**。已完成事项、待办和暂定迭代记录见 [DEV_LOG.md](DEV_LOG.md)；本文件负责说明项目如何配置、运行和验证，不将文件骨架视为已完成业务功能。

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
| `POLYHOUSE_JWT_SECRET` | JWT 的 HS256 签名密钥，32 字节安全随机数据的标准 Base64 编码 | 空；使用 JWT 工具前必须配置，登录接口尚未接入 |

也可以在启动 Maven 的同一个 PowerShell 会话中设置环境变量。以下仅展示非敏感连接参数的设置方式：

```powershell
$env:DB_USERNAME = 'root'
Set-Location E:\project\java\polyethene-polyhouse\polyhouse
mvn spring-boot:run
```

真实密码或密钥应在本地运行配置中填写，不复制进 Git 跟踪的文件，也不在终端输出。已打开的 IDE 不会继承另一个终端后来设置的变量。

## 公共层响应约定

成功响应为 HTTP 200，JSON 字段固定为 `code`、`message`、`data`，成功业务码为 `0`。失败响应保留相同 JSON 结构，`data` 为 `null`。

业务失败通过 `BusinessException(ErrorCode)` 声明。`ErrorCode` 集中定义业务码、默认文案和 HTTP 状态，例如参数错误为 400、未登录为 401、权限不足为 403、资源不存在为 404、冲突为 409、未知异常为 500。

框架产生的 HTTP 错误保留原状态和必要响应头，例如 405 的 `Allow`。错误体固定为 `application/json`，即使客户端请求了不支持的响应格式，也能收到 406 及统一错误体。请求输入、原始异常消息和异常链不进入错误响应；未知异常日志只记录异常类型及应用代码位置。

MyBatis 的 SQL 和绑定参数日志默认关闭。认证拦截器接入后，必须在请求结束时调用 `UserContext.clear()`；线程变量不会自动传递到异步任务。

## 验证

使用 JDK 21，在 `polyhouse/` 中执行：

```powershell
mvn -q compile -DskipTests
mvn -q test
```

在 `polyhouse-web/` 中执行：

```powershell
npm run build
node --test tests/auth-session.test.mjs
```

编译通过和 Spring 上下文启动成功不等于数据库或 Redis 已完成连接验证；实际数据读写需要另行集成测试。普通测试不会执行 MySQL 查询，MySQL 和 Redis 实例测试默认跳过。

## 用户模型与本地 MySQL

`User` 映射现有 `sys_user` 表，角色使用 `UserRoleEnum` 的语义字符串，`ext_json` 映射为 JSON 对象，查询自动排除逻辑删除账号。创建、更新时间由 MySQL 维护，实体写入不会覆盖它们。

`RegisterDTO` 仅接收 `username/password/nickname`，`LoginDTO` 接收 `username/password` 和可选 `clientType`（PC/H5/PLUGIN，省略时默认 PC）。账号约定为 4 至 32 位字母、数字或下划线；昵称非空白且不超过 32 个字符；密码至少 8 个字符，UTF-8 编码最多 72 字节，以适配策划书指定的 BCrypt，不能简单按 72 个字符处理中文密码。不裁剪或归一化密码。DTO 的 JSON 输出和默认文本输出均隐藏密码。Service 已执行输入校验，未来 Controller 仍必须使用 `@Valid` 在 HTTP 入口校验。

`UserProfileVO.from(user)` 仅保留 `id/username/nickname/avatar/role`，不返回密码哈希、状态、登录 IP、软删除标记或任意扩展 JSON。雪花 ID 以字符串传给前端，避免 JavaScript 数字精度丢失。这些模型和 Mapper 不代表注册登录接口已经实现。

初始化基线位于 [polyhouse/sql/001_init.sql](polyhouse/sql/001_init.sql)，按只读策划书归档四张表的结构，不自动执行。现有数据库无需重新初始化；该文件仅供空环境人工建表，不能当作迁移脚本反复执行。账号唯一索引与软删除并存，因此删除后账号仍占用，后续注册不得默认允许复用。

本机核对时四张表的字段、默认值与索引符合策划书；现有表排序规则为 `utf8mb4_0900_ai_ci`，账号判重不区分大小写，本轮保留现状。未来变更字符比较规则必须先评估已有账号，不能直接改表。

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

当前完成签发、验签、配置读取及认证 Service 中的调用，退出和软注销 HTTP 接口已接入，注册/登录/me HTTP 接口及认证拦截器尚未接入。签名校验通过不等于账号仍启用、角色仍有效或会话尚未退出，Service 会继续核对 Redis 和数据库。

`JwtUtils` 保持静态且不持有业务状态：`keyFromBase64` 校验外部密钥，`createToken` 签发访问令牌，`parseAndValidate` 验签后返回内部 `TokenClaims`。密钥、当前时间和有效期由调用方传入，工具不读取环境、不注入 Bean、不访问 MySQL/Redis，也不自动裁剪 `Bearer` 前缀。

仅允许 HS256，固定签发方 `polyhouse`、接收方 `polyhouse-api` 和用途 `ACCESS`。载荷仅包含用户 ID、语义角色、终端类型、随机 `jti` 及签发/过期时间等必要声明；不携带密码、密码哈希、手机号或任意用户资料。JWT 载荷可以解码读取，签名提供防篡改校验，不提供内容保密。

终端类型为 `PC/H5/PLUGIN`，签发时未指定则默认 PC。用户 ID 以字符串 subject 保存；随机 `jti` 保证同一用户同一秒内重复签发的令牌仍可区分。验证会拒绝错误签名、非 HS256 算法、错误归属/用途、缺失或无效身份、压缩载荷、未识别的关键头、未来签发及过期令牌；到达 `exp` 的同一时刻即失效。无效令牌统一抛出不携带原 Token 或异常链的安全异常，由后续认证入口转成 401。

`JwtProperties` 从 `polyhouse.jwt` 读取配置。有效期唯一来源是 `application.yml` 的 `polyhouse.jwt.expiration-days`，当前为七天；已移除 Redis 中重复的七天常量。后续写入白名单时，应使用签发令牌的 `expiresAt` 减去当时时间作为剩余 TTL，避免保存延迟或重新写入延长会话。

密钥必须来自运行环境，禁止使用账号密码、示例短字符串或每次启动临时生成的替代密钥。当前其他模块允许在密钥为空时启动，但调用 `JwtProperties.signingKey()` 时会明确拒绝缺失、错误编码或长度不合格的配置。配置为空不代表 JWT 已能在本机实际使用。

如需在本地终端准备一个开发密钥，可在启动后端的同一个 PowerShell 会话执行以下代码；它只设置进程环境，不打印密钥、不写系统环境或项目文件：

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

`AuthService` 提供 `register/login/getCurrentUser/logout/deregister` 业务方法，其中退出和账号软注销已有 HTTP 路由，目前没有自动拦截普通业务请求。注册返回安全用户资料，不自动登录；登录返回 `LoginVO` 的 `accessToken/tokenType/expiresAt/user`。VO 的默认文本表示隐藏令牌，正常 JSON 响应按需交付令牌。

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

这不等于已完成 IDEA 日常运行密钥配置或浏览器登录验收；相关事项继续在 DEV_LOG 中跟进。

### 退出登录与注销账号的范围

两项接口都使用 `Content-Type: application/json`，请求体为 `{"token":"当前 JWT"}`，不带 `Bearer ` 前缀。凭据通过带校验注解的 `SessionTokenDTO` 接收，不从 URL、用户 ID 或未验证的本地资料决定操作对象。成功响应为 `{"code":0,"message":"操作成功","data":null}`。

| 接口 | 实际行为 |
| --- | --- |
| `POST /api/auth/logout` | 校验签名后原子比较 jti 并撤销当前终端会话；重复退出或旧 Token 退出不会误删新会话，其他终端保留。 |
| `POST /api/auth/deregister` | 要求当前仍在白名单中的有效会话，锁定对应用户后经 MyBatis-Plus 逻辑删除置 `deleted=1`，单条 DEL 清除 PC/H5/PLUGIN 会话；清理失败返回 503 并回滚数据库删除。 |

空白、缺失或超过 4096 字符的 Token 请求返回 400；签名无效、过期，以及注销时会话已被替换或账号失效，返回 401；数据库/Redis 故障返回安全错误，不返回底层异常或凭据。退出允许已被替换但签名仍有效的旧 Token 幂等撤销自己的会话。

前端 `api/auth.ts` 和 `useUserStore.logout()/deregister()` 已可调用：成功后同步清除内存状态与 `polyhouse_token/polyhouse_user`；401 清理失效会话但仍报告操作失败；网络或服务故障保留状态供重试。等待请求期间取得了新 Token 时，旧响应不会清掉新会话。登录界面、个人卡片和会话恢复仍未完成，调用状态方法不等于已有可点击的完整页面。

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
