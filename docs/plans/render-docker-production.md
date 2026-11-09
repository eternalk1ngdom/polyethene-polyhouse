# Render 后端生产适配与容器化计划

2026-11-09 完成方案审阅，本轮交付计划和交接，待在新会话实施。

项目根目录 `E:\project\java\polyethene-polyhouse`，后端位于 `polyhouse/`。规划基线为 `feature/avatar-compression-db` 的 `21347b1`，`dev` 和 `main` 均为 `0c353c3`。新会话先核对实际状态。当前特性分支包含数据库头像、统一建表、屋主初始化及双端登录，可以直接作为本地容器验收的基础。

## 1. 目标与边界

- Render 新加坡免费 Web Service 运行 Spring Boot，按 0.1 CPU、512 MB 的实例预算验收。连接 TiDB Cloud 和 Upstash Redis 的新加坡实例，前端继续由 GitHub Pages 托管。免费层以小范围试用为目标，冷启动和服务配额属于实际验收条件。
- 本地开发继续读取现有 `application.yml` 与私有运行预设。当前没有 `application-dev.yml` 或显式默认 `dev`，保留这个行为。生产覆盖项单独放在 `polyhouse/src/main/resources/application-prod.yml`，容器启动时激活。
- 延用现有 JDK、Maven 项目和依赖版本。用户已核对 TiDB Cloud 的 21 条 CHECK，云端屋主初始化日志显示提交成功。上线工作复用现有云库，数据或初始化变更需另行授权。
- 本地实现、实际容器验证、远端部署、前端切换分步推进。推送、PR、合并、远端设置变更、云资源操作和发布分别按用户授权执行。其他 P5 项目仍沿用原批准边界。

## 2. 构建与生产配置

| 文件或位置 | 预定改动 |
| --- | --- |
| 根目录 `Dockerfile` | 使用 `maven:3.9-eclipse-temurin-21-alpine` 在 `/build` 构建。将 `polyhouse/pom.xml` 和 `polyhouse/src/` 复制到对应位置，执行 `mvn clean package -DskipTests`。从构建阶段复制 Spring Boot 可执行 JAR 到 `eclipse-temurin:21-jre-alpine` 的 `/app/app.jar`，以非 root 用户运行。构建阶段编译和打包，测试在验收步骤另跑。 |
| 根目录 `.dockerignore` | 从仓库根构建时仅允许后端所需文件进入构建上下文。排除 Git、IDE 私有配置、环境文件、凭据和证书私钥、备份、旧构建产物、前端及其他工作树。核对后端源码目录内的本地覆盖配置也被排除。 |
| `polyhouse/src/main/resources/application-prod.yml` | 只存生产覆盖和环境变量引用。监听 `0.0.0.0`，由 Spring 用 `${PORT:8080}` 读取运行时端口。独立配置 TiDB、Upstash、JWT、连接池、请求并发和日志。 |
| CORS 与相关测试 | 复用现有 `CorsFilter` 和统一拒绝响应。仅生产 profile 扩展指定来源和方法，保留本地默认行为。测试允许及拒绝路径。 |

### JVM 和端口

- 使用 JSON 形式的 `ENTRYPOINT` 直接启动 Java，容器可以把停止信号交给 JVM。由 Spring 绑定运行时端口，`EXPOSE 8080` 只记录镜像默认端口。Render 默认通过 `PORT=10000` 转发，也要验证自定义端口。 [Dockerfile 启动语义](https://docs.docker.com/reference/dockerfile/#entrypoint) · [Render 端口绑定](https://render.com/docs/web-services#port-binding)
- 初始参数为 `-Xms64m -Xmx224m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:MaxMetaspaceSize=128m -Xss512k -Dspring.profiles.active=prod`。原提议的 65% 堆上限约为 333 MiB，与 128 MiB 元空间上限相加后留给堆外的预算太窄。实际通过容器峰值判断是否需要调整堆或 JVM 直接内存上限。线程栈、JIT、直接缓冲区和原生分配均计入验收。 [JDK 21 JVM 参数](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html)
- 生产 profile 先关闭虚拟线程，让 Tomcat 工作线程限制生效。建议从最大 16、最小空闲 2 开始，同时控制连接和等待队列。Hikari 先用最大 3、最小空闲 1。连接池等待、JDBC 建连与读超时、Redis 命令超时分别验证。连接最大寿命按 TiDB 实际断开策略和测试结果确定，避免无依据地每三分钟换连接。 [Spring Boot 3.3 配置](https://docs.spring.io/spring-boot/3.3/appendix/application-properties/) · [HikariCP 参数](https://github.com/brettwooldridge/HikariCP#frequently-used)

### 外部连接和凭据

| 变量 | 用途 |
| --- | --- |
| `PORT` | Render 注入容器的监听端口。本地容器缺省用 8080。 |
| `DB_HOST`、`DB_PORT`、`DB_NAME` | TiDB 连接面板提供的主机和端口，默认端口 4000，目标数据库为 `polyhouse`。 |
| `DB_USERNAME`、`DB_PASSWORD` | 沿用项目已有变量命名。使用完整 TiDB 用户名及应用运行凭据，和网站屋主、数据库初始化权限分开。 |
| `REDIS_HOST`、`REDIS_PORT`、`REDIS_USERNAME`、`REDIS_PASSWORD` | Upstash 的原生 Redis TCP 连接信息。生产启用 TLS，按控制台要求填写用户名，并使用可写 Token。 |
| `POLYHOUSE_JWT_SECRET` | 生产签名密钥，按现有契约使用 32 字节随机值的标准 Base64。生产启动时验证，实例重启复用同一密钥。 |

- TiDB 使用 JDBC `sslMode=VERIFY_IDENTITY`，同时校验 CA 和主机名。确认实际镜像信任链和目标端点，不在构建镜像时连接数据库。Upstash 使用已有 Lettuce 客户端，验证 TLS、写入、删除、Lua 会话撤销和 TTL。 [TiDB JDBC](https://docs.pingcap.com/developer/dev-guide-sample-application-java-jdbc/) · [Upstash TCP 连接](https://upstash.com/docs/redis/howto/connect-client)
- 生产变量仅由 Render 注入。本地容器测试使用专用临时凭据和已忽略的文件，例如 `.env.render.local`，先用 `git check-ignore` 核对。当前普通 `application-prod.local.yml` 未被忽略，避免用这类文件存私密值。检查 Git 暂存、Docker 构建上下文及镜像配置中均没有真实凭据。

### CORS、请求体与健康检查

- 生产 CORS 使用精确来源 `https://eternalk1ngdom.github.io`、`http://localhost:5173` 和 `http://localhost:3000`，允许 `GET, POST, PUT, DELETE, OPTIONS` 以及 `Authorization`、`Content-Type`、`Accept`。确认合法的预检在鉴权前完成，未知来源被拒绝，实际受保护请求仍需 Token。当前登录使用 Bearer，建议保留 `allowCredentials=false`。Cookie 登录如有明确需求，再评估单独的凭据与跨站安全契约。 [现有 CORS 实现](../../polyhouse/src/main/java/com/polyethene/polyhouse/config/CorsConfig.java) · [Fetch 跨域规范](https://fetch.spec.whatwg.org/#cors-protocol-and-credentials)
- 保留关闭 multipart 的现状。头像上传和审核已经使用 JSON，先清点最大合法请求，再定生产请求体上限。若实现生产请求体限制，要在 JSON 解析前拒绝超限并返回 413，同时覆盖带 `Content-Length` 与分块传输，保留正常 CORS 和现有 Base64 字段校验。
- Render 首次启动可用默认 TCP 健康检查验证监听。若需要 HTTP 就绪检查，使用低成本的专用路径并验收实际语义。TiDB 与 Redis 连通性通过独立冒烟核对，避免每次探活都调用外部服务。 [Render 健康检查](https://render.com/docs/health-checks)

## 3. 本地验收

1. 检查 Git 差异及忽略规则，执行 `mvn -q compile -DskipTests` 和受影响的后端测试。以既有本机运行方式做一次回归，确认无需任何生产变量也能正常启动，本地 CORS 行为与基线一致。测试账号使用隔离库。
2. 从仓库根运行 `docker build -t polyhouse:prod .`。使用 `--memory=512m --memory-swap=512m --cpus=0.1` 和临时凭据运行镜像。验证默认端口、覆盖 `PORT=10000`、有效 profile、非 root 用户和正常停止。核对缺失或无效生产配置时的提示，并检查日志是否包含敏感值。
3. 用隔离实例实测 CORS 正反例和手机/电脑登录、单端退出、头像提交和审核、Redis 会话和额度。分别测容器冷启动、重复重启、正常并发及异常大请求的表现。记录总内存峰值、启动时间、响应耗时、退出码及容器 `OOMKilled` 状态。高位持续贴近上限或出现 OOM、重启、长时间阻塞时先调整预算和并发再验。
4. Docker 引擎或隔离外部服务不可用时，保留可完成的测试证据并标明阻塞。容器在 512 MB 限额下的运行结果必须实际测得。

## 4. 线上验证与回退

1. 本地验收后汇报分支、提交和测试结果，再按授权安排 Git 晋级及 Render 服务。创建时选择新加坡，使用仓库根的 Dockerfile 和构建上下文，凭据从 Render 注入。先验证 Render 后端与 TiDB、Upstash 的连接和资源用量。 [Render 区域](https://render.com/docs/regions)
2. 验证免费层休眠唤醒和前端 12 秒请求超时的交互。记录第一次和唤醒后一次请求的耗时。若有明显超时，评估前端等待或手动重试提示，并单独确认范围。Render 公布的典型唤醒时间约一分钟。 [Render 免费层](https://render.com/docs/free#spinning-down-on-idle)
3. 云后端通过后，再按授权设置 GitHub Pages 构建变量 `VITE_API_BASE_URL=https://<render-host>/api`。从 Pages 真实来源复核登录和头像审核、两端会话、重新启动后头像可读，以及非白名单来源拒绝。
4. 若后端候选出现内存超限、连续健康检查失败、鉴权或持久化故障，暂停切换。后端保留上一已验提交供回退，前端保留上一构建配置和产物。首次上线没有可回退后端时，暂停对外试用并修复候选。数据库和 Redis 的数据操作遵循单独备份、恢复和授权流程。

实际结果记录在 [dev.13](../dev-log/v1.0-dev.13.md)，发布条件按[云端上线验收清单](../dev-log/launch-preparation.md)核对。
