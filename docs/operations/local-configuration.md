# 本地配置参考

[首次运行](getting-started.md) · [返回首页](../../README.md)

## 变量与作用范围

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
| `REDIS_SSL` | Redis TLS 开关 | `false`，生产 profile 固定启用 |
| `PORT` | 后端监听端口 | `8080` |
| `POLYHOUSE_JWT_SECRET` | JWT 的 HS256 签名密钥，32 字节安全随机数据的标准 Base64 编码 | 配置默认空；本机固定值由私有预设提供 |

也可以在启动 Maven 的同一个 PowerShell 会话中设置环境变量。以下仅展示非敏感连接参数的设置方式：

```powershell
$env:DB_USERNAME = 'root'
Set-Location E:\project\java\polyethene-polyhouse\polyhouse
mvn spring-boot:run
```

真实密码或密钥应在本地运行配置中填写，不复制进 Git 跟踪的文件，也不在终端输出。已打开的 IDE 不会继承另一个终端后来设置的变量。

## 已有本机认证预设

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

IDEA 绿色 Run/Debug 按钮需另绑启动前外部工具，步骤见[Redis 启动前配置](../../docs/learning/backend-basics.md#本地-redis)；这是启动检查，不是运行期监控。

入口只读取允许的环境变量名，导入当前进程后调用 Maven，并在成功或失败退出时恢复原环境；不修改系统环境，不执行 XML 内容。实例验收限制为本机 `polyhouse_db` 和本机 Redis，避免误继承外部连接。IDEA 自身启动应用时直接使用同一份个人配置。

新机器缺少预设时，先在 IDEA 建立 `PolyhouseApplication` 运行配置，再执行 `.\polyhouse\scripts\Initialize-LocalAuth.ps1`，通过隐藏输入提供数据库密码。初始化只在密钥缺失时生成一次 32 字节安全随机密钥；重复执行保留已有账号、密码和密钥，无效旧密钥会明确拒绝而非静默替换。测试隔离用例仍可使用临时随机密钥，专门的运行配置用例会验证这里保存的固定密钥。

本机 MySQL 与 TiDB Cloud 的新库均执行 [001_schema.sql](../../polyhouse/sql/001_schema.sql)。备份、首个屋主初始化和回退见[数据库初始化说明](../../docs/operations/database-bootstrap.md)。应用启动时不自动建表，已有库应按实际结构单独迁移。账号与资料运维见[账号安全](../../docs/operations/account-security.md)、[屋主开户](../../docs/operations/account-provisioning.md)、[账号管理](../../docs/operations/account-management.md)和[资料审核](../../docs/operations/profile-moderation.md)。


## 生产地址与历史变量

默认本地前端通过 Vite `/api` 代理访问本机 8080。外部后端由前端运行或构建环境设置 `VITE_API_BASE_URL=https://polyethene-polyhouse.onrender.com/api`，已有构建需要重新构建才会改变地址。生产使用 `application-prod.yml` 和平台注入变量，详见 [Render 生产计划](../plans/render-docker-production.md)。

根站由独立 Pages 仓库固定源码提交发布，项目子路径由本仓库 `main` 工作流发布，两者部署来源见[上线清单](../dev-log/launch-preparation.md)。头像现存数据库，根 `.env.example` 留有历史目录示例 `POLYHOUSE_AVATAR_DIRECTORY`，当前应用配置未读取它。[头像存储说明](avatar-storage.md)
