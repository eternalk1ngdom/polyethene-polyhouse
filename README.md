# 聚妙屋 Polyhouse

聚妙屋是一个日常工具箱。妙替在浏览器中批量替换文本，妙压按目标体积处理图片。文件处理在本机完成，账号系统提供登录、成长、资料审核和受控账号管理。

当前为 v1.0 开发阶段，已经部署试用。实现状态与发布证据见 [DEV_LOG.md](DEV_LOG.md)，阶段号与正式版本标签分别管理。

## 选择使用方式

| 你想做什么 | 从哪里开始 |
| --- | --- |
| 直接使用 | [打开聚妙屋](https://eternalk1ngdom.github.io/)；另有[项目站入口](https://eternalk1ngdom.github.io/polyethene-polyhouse/) |
| 本机使用妙替、妙压 | 按下面的前端快速开始操作 |
| 本机运行登录、账号和审核 | [完整首次运行指南](docs/operations/getting-started.md) |
| 参与开发、提交修复 | [贡献指南](CONTRIBUTING.md) |

线上账号由屋主创建。公共注册已关闭，需要登录时请向站点维护者申请账号。Render 免费实例空闲后需要唤醒，首次登录可能等待约一分钟。使用方法、数据保存范围及全部功能限制见[使用指南](docs/guides/features.md)。

## 已实现的功能

| 模块 | 能力和主要边界 |
| --- | --- |
| 妙替 | 文件或文件夹勾选、字面替换、全文计数、有界预览与定位、ZIP、本机词库、整库备份、规则链接、可选草稿。最多 500 个候选，实际处理 20 文件、单个 2 MiB、合计 5 MiB，输入手选 UTF-8/GBK/GB18030，输出 UTF-8。 |
| 妙压 | 单图或批量目标体积、JPEG DPI、成功项 ZIP、取消和重试。最多 20 张、合计 60 MiB，单张 20 MiB/4000 万像素。换底、PDF 与云预设不在已实现范围。 |
| 账号 | JWT 与 Redis 会话，电脑/移动端独立登录、资料和成长、退出和软注销、首次改密。屋主可创建普通用户、管理员、内部用户，初始密码一次交付；昵称和头像先审后发。 |

完整规则及失败、恢复、隐私边界请查[使用指南](docs/guides/features.md)。例如词库、草稿和分享链接包含明文规则，浏览器本地数据属于站点来源，退出账号不会清除。

## 前端快速开始

先安装 Git 与满足 [package.json](polyhouse-web/package.json) 引擎范围的 Node.js。当前声明为 `^22.18.0 || >=24.12.0`，锁文件还包含更严格的传递依赖要求，本轮干净安装使用 Node 24.22.1 / npm 11.19.1。版本声明差异和完整环境要求见[首次运行指南](docs/operations/getting-started.md)。

在 PowerShell 中依次执行，克隆目录可以自行选择。

```powershell
git clone https://github.com/eternalk1ngdom/polyethene-polyhouse.git
Set-Location polyethene-polyhouse/polyhouse-web
npm ci
npm run dev
```

打开终端实际给出的地址，默认是 `http://localhost:5173/polyethene-polyhouse/#/`。在首页进入妙替或妙压，用小文件尝试处理并下载结果。只运行前端时账号功能需要另行启动后端。[完整本地部署](docs/operations/getting-started.md)说明了空库初始化、Redis、密钥、首个屋主以及登录验证。

## 项目地图

| 目录 | 职责 |
| --- | --- |
| `polyhouse/` | JDK 21、Spring Boot 3、MyBatis-Plus 后端及 SQL |
| `polyhouse-web/` | Vue 3、TypeScript、Vite、Element Plus 前端 |
| `polyhouse-web/src/core/`、`src/workers/` | 纯 TS 算法、Worker 协议与任务处理 |
| `polyhouse/scripts/` | 本地运行、屋主运维和隔离验收入口 |
| `docs/operations/` | 配置、数据库、账号与发布操作 |
| `docs/design/`、`docs/plans/`、`docs/dev-log/` | 设计、批准范围、实施与验证记录 |

## 开发与验证

修改前完整阅读 [AGENTS.md](AGENTS.md)，按[贡献指南](CONTRIBUTING.md)准备环境、定位模块并从 `dev` 建任务分支。只读策划书通过[设计索引](docs/design/blueprint-index.md)按需查阅。

依赖准备完成后，在仓库根目录可运行普通验证。

```powershell
.\polyhouse\scripts\Test-Project.ps1
```

这个入口执行后端编译和普通测试、前端静态检查、测试与构建。真实数据库、Redis 和浏览器有独立隔离入口，具体命令、跳过含义、测试地图及容量基准见[验证指南](docs/operations/verification.md)。

## 查阅与反馈

- 运行失败先看[首次运行与排错](docs/operations/getting-started.md)，已有 IDEA 环境看[配置参考](docs/operations/local-configuration.md)。
- MySQL/TiDB 建库、迁移、屋主初始化和回退见[数据库说明](docs/operations/database-bootstrap.md)及[已有账号屋主初始化](docs/operations/owner-bootstrap.md)。
- 接口和运维见[账号安全](docs/operations/account-security.md)、[开户](docs/operations/account-provisioning.md)、[管理](docs/operations/account-management.md)、[资料审核](docs/operations/profile-moderation.md)和[头像存储](docs/operations/avatar-storage.md)。
- 实现原理见[后端基础](docs/learning/backend-basics.md)，当前决策与任务见[计划索引](docs/plans/README.md)，线上专项状态见[上线清单](docs/dev-log/launch-preparation.md)。
- 反馈问题或参与贡献见 [CONTRIBUTING.md](CONTRIBUTING.md)。当前仓库没有项目许可证文件，使用和分发授权需与维护者确认。
