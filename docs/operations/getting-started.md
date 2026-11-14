# 首次运行与排错

[返回首页](../../README.md) · [配置参考](local-configuration.md) · [验证入口](verification.md)

## 选择运行范围

| 范围 | 需要准备 |
| --- | --- |
| 在线体验 | 浏览器，访问[聚妙屋](https://eternalk1ngdom.github.io/)，账号向屋主申请 |
| 本地工具 | Git、Node.js/npm，先按[前端快速开始](../../README.md#前端快速开始)启动页面 |
| 完整本地栈 | 上述环境、JDK 21、Maven、MySQL 8.0、Redis，按下文顺序操作 |

本文命令使用 Windows PowerShell。Linux/macOS 可以使用相同的应用和变量，但 PowerShell、WSL 和 Windows Java classpath 分隔符需要按平台调整，本轮没有实测这些平台。

2026-11-14 的隔离验证已通过前端干净安装、空库导入、首次屋主命令、后端启动和 Vite 代理。当前机器 Redis 不可达、浏览器工具超时，本文最后的浏览器工具操作及登录刷新尚待实测，状态见[阶段日志](../dev-log/v1.0-dev.14.md)。

## 1 · 检查环境

准备 [JDK 21](https://adoptium.net/temurin/releases/?version=21)、[Maven](https://maven.apache.org/install.html)、[Node.js](https://nodejs.org/en/download)、[MySQL 8.0](https://dev.mysql.com/doc/refman/8.0/en/installing.html)和[Redis](https://redis.io/docs/latest/operate/oss_and_stack/install/)。将客户端加入当前终端的 PATH，数据库与 Redis 安装后启动各自服务。

```powershell
git --version
java -version
mvn --version
node --version
npm --version
mysql --version
```

本轮机器为 JDK 21.0.12.1、Maven 3.9.4、Node 24.22.1、npm 11.19.1、MySQL 客户端 8.0.34。项目的实际声明以 [pom.xml](../../polyhouse/pom.xml)、[package.json](../../polyhouse-web/package.json)和锁文件为准。Node 引擎声明与锁内传递依赖、项目 Pages 工作流的 Node 20 存在差异，首次安装选择已验证的 Node 24.22.1；这里记录环境差异，依赖和工作流版本沿用仓库现状。

在自己选择的工作目录克隆仓库，后续 `$projectRoot` 始终表示这个副本的绝对路径。

```powershell
git clone https://github.com/eternalk1ngdom/polyethene-polyhouse.git
Set-Location polyethene-polyhouse
$projectRoot = (Get-Location).Path
```

## 2 · 为新副本创建空数据库

这一节用于全新的独立数据库。已有库按[迁移与回退说明](database-bootstrap.md)核对结构。维护者的正式库和云库保留原数据。

在仓库根目录打开 MySQL 客户端，用 `-p` 的交互提示输入数据库密码。

```powershell
mysql --default-character-set=utf8mb4 --host=127.0.0.1 --port=3306 --user=root -p
```

在 MySQL 客户端中执行以下 SQL。`polyhouse_local` 是本文示例的新库名，可以换成自己的独立名称。建库需要该数据库账号有相应权限。

```sql
CREATE DATABASE polyhouse_local CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE polyhouse_local;
SELECT DATABASE();
SHOW TABLES;
source polyhouse/sql/001_schema.sql
SHOW TABLES;
SELECT COUNT(*) FROM sys_user;
quit
```

预期建表前 `SHOW TABLES` 为空，建表后有 13 张表，`sys_user` 行数为 0。客户端显式选择 `utf8mb4`，防止本机 GBK 默认值改变生成列元数据并导致屋主校验拒绝。`source` 路径相对于启动客户端的目录，找不到文件时用自己副本的绝对路径并将反斜杠换成 `/`。表已存在时报错，应回头检查库名和当前结构。应用启动不会自动建表。[空库基线](../../polyhouse/sql/001_schema.sql)也保留了已有库升级语句的注释，整份运行会跳过注释。

## 3 · 准备 Redis

Windows 可以按 [Redis 的 Windows 安装说明](https://redis.io/docs/latest/operate/oss_and_stack/install/archive/install-redis/install-redis-on-windows/)准备 WSL。项目现有辅助脚本针对名为 `Ubuntu`、启用 systemd 的 WSL Redis，使用本机 `127.0.0.1:6379` 且无认证。

```powershell
# 安装 Redis 后，先检查发行版和服务。
wsl --list --verbose
wsl -d Ubuntu -- systemctl is-active redis-server
wsl -d Ubuntu -- redis-cli ping
# 在仓库根目录按需启动并保持本机 Redis 可用。
.\polyhouse\scripts\Ensure-LocalRedis.ps1
```

成功标志为 `PONG` 和脚本的 `Local Redis is ready (PONG)`。脚本只检查和启动既有服务，安装及 WSL 配置由你准备。使用其它 Redis 安装时自行验证 PING，并在下一节设置对应连接。持续可用及 IDEA 启动前绑定见[本地 Redis 说明](../learning/backend-basics.md#本地-redis)。

## 4 · 在后端终端设置私有变量

在仓库根目录的一个 PowerShell 终端设置变量。数据库账号和密码是第 2 步连接 MySQL 使用的凭据。`Read-Host` 输入的真实密码不会进入命令文本历史。

```powershell
$projectRoot = (Get-Location).Path
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/polyhouse_local?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
$env:DB_USERNAME = Read-Host '数据库账号'
$dbSecret = Read-Host '数据库密码' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $dbSecret).Password
Remove-Variable dbSecret
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '6379'
$env:REDIS_DATABASE = '0'
$keyBytes = [byte[]]::new(32)
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$random.GetBytes($keyBytes)
$random.Dispose()
$env:POLYHOUSE_JWT_SECRET = [Convert]::ToBase64String($keyBytes)
[Array]::Clear($keyBytes, 0, $keyBytes.Length)
Remove-Variable keyBytes, random
```

JWT 密钥用于签名和核验登录令牌。上面的随机密钥只在当前终端及子进程有效，关闭后若换密钥，原会话需要重新登录。长期开发可按[配置参考](local-configuration.md)保存在私有 IDEA 运行配置中。Redis 有 ACL 或密码时同样通过私有变量设置 `REDIS_USERNAME`、`REDIS_PASSWORD`。示例的 MySQL 非 TLS 连接仅用于本机，云端按[生产配置计划](../plans/render-docker-production.md)使用证书校验。

根 `.env.example` 只作变量示例，复制为 `.env` 不会自动注入 Spring Boot。数据库密码、JWT 密钥属于后端私有运行环境。前端只需要 API 地址。当前头像持久化到数据库，`.env.example` 的历史 `POLYHOUSE_AVATAR_DIRECTORY` 示例已不是现行配置读取项。[完整变量表](local-configuration.md)

## 5 · 创建首个屋主并启动后端

保持第 4 步的终端，在 `polyhouse/` 中执行。先编译并生成运行 classpath，再运行独立初始化命令。

```powershell
Set-Location (Join-Path $projectRoot 'polyhouse')
mvn -q compile dependency:build-classpath '-Dmdep.outputFile=target/owner-classpath.txt' '-DincludeScope=runtime'
# 上一步成功后继续。
$ownerClassPath = 'target/classes;' + (Get-Content -LiteralPath 'target/owner-classpath.txt' -Raw).Trim()
java -cp $ownerClassPath com.polyethene.polyhouse.ops.OwnerBootstrapCommand --create --username=root --apply
```

按提示输入并确认网站屋主密码。它与数据库账号密码分别管理。成功标志为日志显示「空库屋主创建已提交」。该命令仅用于空库首次建屋主，存在用户或初始化记录时会拒绝。已有账号的 `Initialize-Owner.ps1` 另见[屋主运维](owner-bootstrap.md)，首次建库用上面这条命令。

```powershell
mvn spring-boot:run
```

等待日志显示 `Tomcat started on port 8080` 和应用启动成功。保持这个终端运行。在另一个终端请求 `http://localhost:8080/api/auth/me` 应收到 HTTP 401，JSON 业务码为 `40100`。这是未登录时的正常响应，完整数据连通性由下一步登录确认。

## 6 · 启动前端并验证完整链路

新开终端，进入克隆副本的 `polyhouse-web/`。默认开发模式通过 Vite 把 `/api` 转发到本机 8080。

```powershell
npm ci
npm run dev
```

打开 `http://localhost:5173/polyethene-polyhouse/#/`，如果端口被占用，以 Vite 显示的端口为准。用第 5 步创建的网站 `root` 及网站密码登录。看到屋主身份后刷新，确认仍能读取身份和账号列表，即可确认前端、后端、数据库及 Redis 的主要链路。屋主从右上角进入工作台，按[开户指南](account-provisioning.md)创建其它账号，首次交付初始密码后用户需改密。

如果只是本地工具试用，准备 Node 后即可按本节启动前端，先试小文本或小图片的处理和下载。账号入口在完整后端未启动时会显示连接问题。

在对应终端按 `Ctrl+C` 停止前端和后端。确认属于自己的临时库后再按 MySQL 运维方式清理，平时开发可保留库与私有预设供下次使用。

## 7 · 常见问题

| 现象 | 检查和下一步 |
| --- | --- |
| `java`、`mvn` 或 `npm` 找不到 | 安装相应工具，重开终端，核对 PATH 和 `JAVA_HOME`。`mvn --version` 的 Java 应为 21。 |
| `npm ci` 报引擎不支持或原生模块失败 | 核对 Node 及锁文件中的引擎要求，按已验证版本重试。保留锁文件和错误原文供排查。 |
| MySQL 拒绝连接、`Access denied` | 用同一账号运行第 2 步客户端连接，检查服务、端口和权限，再核对当前后端终端的变量。 |
| 找不到表、空库屋主创建被拒绝 | `SELECT DATABASE()`、`SHOW TABLES`，确认 URL 指向本轮新库。已有库按实际迁移，不覆盖建库。 |
| 屋主命令提示数据库保障未就绪 | 核对索引、生成列与客户端字符集。本轮 Windows 客户端默认 GBK，空库须使用上面的 `utf8mb4` 导入。已有库先按数据库运维说明核对并备份。 |
| Redis 脚本超时或 WSL 挂起 | 先查发行版能否进入，再查服务状态和 PONG。准备依赖后再启动应用，Windows WSL 专属操作见[Redis 说明](../learning/backend-basics.md#本地-redis)。 |
| 后端启动了但登录失败 | 核对数据库与 Redis、JWT 密钥格式、网站账号和网站密码。记录时间与脱敏错误，启动成功不能代替真实登录。 |
| 首页 404、资源丢失 | 本地默认访问 `/polyethene-polyhouse/`，路由在 `#` 后。查看 Vite 终端实际地址。 |
| 端口占用 | 先识别监听进程，使用另一个端口或关闭自己启动的进程。本机后端换端口时需同步前端连接。 |
| 前端误连线上 | 查前端启动终端或 `polyhouse-web/.env*` 的 `VITE_API_BASE_URL`。默认本地开发留空，云端生产构建使用 Render HTTPS 地址加 `/api`。 |
| 修改环境变量后 IDEA 无效 | 已启动的 IDE 不继承另一个终端后来设置的变量，在 IDEA 自己的运行配置设置并重启应用。 |
| 测试报告出现 skipped | 按[验证入口](verification.md)确认条件开关。普通测试跳过实例测试不代表数据库验收成功。 |

反馈时带操作系统、工具版本、运行目录、命令、发生时间、预期与实际结果及脱敏错误。凭据输入留在自己的终端，敏感问题通过[贡献指南](../../CONTRIBUTING.md)说明的方式联系维护者。
