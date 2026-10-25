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
| `POLYHOUSE_JWT_SECRET` | 后续 JWT 签发与验证密钥 | 空；当前尚未实现 JWT 功能 |

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
```

编译通过和 Spring 上下文启动成功不等于数据库或 Redis 已完成连接验证；实际数据读写需要另行集成测试。普通测试不会执行 MySQL 查询，Redis 实例测试默认跳过。

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

需要同时运行全部测试并验证 Redis 时执行：

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
