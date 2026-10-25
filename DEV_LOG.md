# 聚妙屋 Polyhouse 开发日志

当前里程碑：**v1.0 开发中，未发布**。最后整理日期：2026-10-26（Asia/Shanghai）。

本文件记录已经发生的变更、验证结果和剩余工作。原文件中的 Stage 1 任务已归入下面的进度清单，不再作为一条要求立即实现全部功能的任务提示。

## 文档职责与状态约定

- [AGENTS.md](AGENTS.md)：唯一长期工程约束；本日志不复制或替代它。
- [Polyhouse_Ultimate_Master_Blueprint.docx](Polyhouse_Ultimate_Master_Blueprint.docx)：只读策划书，说明目标能力和架构设计。
- [README.md](README.md)：项目使用入口，记录环境配置、启动、连接和测试方法。
- 本日志：记录实际进度。此处列出的未完成事项不构成自动实施或部署授权。

`[x]` 表示在所注明的范围内完成；`[ ]` 表示尚未完成验收。只有文件、目录、注解或配置存在，不能据此认定对应业务功能完成。用户本机确认与自动化验证分别标注。

## v1.0 已完成事项

### 工程与仓库基线

- [x] 后端 `polyhouse/` 和前端 `polyhouse-web/` 使用同一个根 Git 仓库，分支为 `main`；无前后端嵌套仓库。
- [x] 根 `.gitignore` 统一排除依赖、构建、IDE、日志、运行产物和真实环境文件，保留锁文件与无敏感值的 `.env.example`。
- [x] 根 `.gitattributes` 统一文本 LF、Windows 脚本 CRLF 和指定二进制文件处理方式。
- [x] 规范化 `AGENTS.md` 文件名及策划书引用；未创建重复约束的 `SKILL.md`，策划书保持原名和只读内容。
- [x] 补齐后端根包 `com.polyethene.polyhouse` 下的分层目录，以及前端 `api/config/core/workers/components/stores/views` 等目录和文件骨架。部分零字节 `.gitkeep` 暂留，无运行影响。
- [x] 初始基线提交 `b61ca10` 已推送到 `https://github.com/eternalk1ngdom/polyethene-polyhouse.git` 的 `main`。后续提交和同步状态以 Git 历史及远端核验为准，不将提交等同于正式发布。

### 后端公共基础层

- [x] `Result<T>`：统一 `code/message/data` 响应字段，成功业务码为 `0`，支持携带 VO 的成功响应和统一失败响应。
- [x] `ErrorCode` 与 `BusinessException`：集中维护业务错误码、安全提示和 HTTP 状态，拒绝缺失错误类型及空白错误提示。
- [x] `GlobalExceptionHandler`：覆盖请求体、表单绑定、类型转换和约束校验；区分 Spring MVC 方法入参校验错误与返回值校验错误。
- [x] 异常 HTTP 语义：参数错误使用 400，未登录/权限错误定义为 401/403，未知异常使用 500；框架 404/405/406/415/503 等状态及必要响应头得到保留。
- [x] 错误体固定为 JSON；已修复不兼容 `Accept` 导致 406 丢失统一响应体的问题，测试覆盖 405 的 `Allow` 响应头。
- [x] `UserRoleEnum`：`USER/INSIDER/OWNER` 编码、`10/50/99` 权重判断及 JSON 语义字符串序列化。已声明 MyBatis-Plus 枚举映射，但尚未用真实用户表验证往返映射。
- [x] `@RequireRole`：方法/类型上的运行时最低角色声明。仅注解已完成，实际接口权限拦截尚未实现。
- [x] `UserContext`：原子设置用户 ID 与角色、查询及 `clear()`；验证平台线程、虚拟线程的身份隔离与线程复用后的清理。请求结束时自动清理仍待拦截器接入。
- [x] `RedisConstants`：集中定义鉴权键、默认 PC 终端、五次失败上限、十五分钟窗口和七天白名单生命周期。常量完成不代表防暴破或登录功能已实现。

### 配置、安全与本机环境

- [x] 数据库密码和 JWT 密钥改为环境变量引用，示例文件不保存真实凭据；README 明确当前 Spring Boot 不自动加载根 `.env`。
- [x] 未知异常日志仅记录类型和应用代码位置，不输出原始消息、请求输入或异常链；MyBatis SQL 及绑定参数日志默认关闭，并有实际配置断言。
- [x] Windows/Codex 中 `JAVA_HOME`、`java`、`javac` 和 Maven Runtime 均已确认使用 JDK 21；后端编译通过。本机 IDE SDK 由用户维护，不修改系统安装文件。
- [x] MySQL `polyhouse_db` 及 `sys_user`、`tool_replace_template`、`tool_targetkb_preset`、`tool_user_preset` 四张表：用户确认已建立并能连接。尚不代表项目 Mapper 或业务 SQL 已完成联调。
- [x] Ubuntu WSL2 Redis 7.0.15 已确认 `active/enabled`，开启 systemd，Windows 能通过 `127.0.0.1:6379` 访问。仅核查现有服务，未重装或修改系统配置；随 Ubuntu 启动不等于保证随 Windows 登录启动。
- [x] Spring Redis 接入：沿用已存在的 Redis Starter/Lettuce，补齐主机、端口、数据库编号、可选认证、连接超时和命令超时；`RedisConfig` 使用 UTF-8 字符串序列化键、值和 Hash 字段，不使用 Java 原生对象序列化。
- [x] Redis 实例测试：通过 PING、中文文本读写、计数递增、TTL 及清理验证；测试键随机命名并设置 60 秒过期，只清理本次创建的键。测试显式启用，普通测试不要求本机 Redis 在线。
- [x] IDEA Redis 连接：用户截图确认 `Polyhouse Local Redis`、单机模式、`localhost:6379`、数据库 `0`、无身份验证连接成功，延迟约 15 毫秒。

### 前端基础与验证

- [x] Vue 3、TypeScript、Vite、Pinia 和 Vue Router 基础脚手架存在；Element Plus 及图标等所需依赖已经安装，尚未完成 UI 接入。
- [x] 移除默认演示内容，保留应用挂载和路由出口；`ToolRegistryItem` 与 `VirtualFileEntry` 类型已建立。注册表和路由目前仍为空，不计作工具导航或页面功能完成。
- [x] 后端编译、前端 TypeScript 检查和 Vite 生产构建均已通过。

## v1.0 未完成事项

以下按策划书顺序跟进，不以文件数量或主观百分比估算完成度。v1.0 最终发布范围尚未冻结，正式发布前按实际验收结果收口。

### Stage 1 用户模型与鉴权闭环

- [ ] 核对现有四张表与策划书的字段、索引、语义角色、逻辑删除及 `ext_json`；形成可追踪的初始化 SQL，并验证实体映射。
- [ ] 完成 `User`、`RegisterDTO`、`LoginDTO`、脱敏 `UserProfileVO` 和 `UserMapper`，验证 DTO 校验及 Entity 不直接跨越接口边界。
- [ ] 完成 `PasswordUtils` 的 Hutool BCrypt 和 `JwtUtils` 的 HMAC-SHA256；统一 JWT 有效期和 Redis 白名单 TTL 来源，避免双份配置漂移。
- [ ] 完成 `AuthService/AuthServiceImpl` 注册、判重、密码校验、账号状态检查，以及数据库唯一冲突处理。
- [ ] 完成十五分钟内五次失败锁定十五分钟、账号不存在与密码错误统一提示，验证并发计数及过期行为。
- [ ] 完成 JWT 与 Redis 多端白名单、默认 PC 及 H5/PLUGIN 预留、退出后令牌失效；不得把当前计数器集成测试当作完整登录安全验收。
- [ ] 完成 `AuthInterceptor`、`WebMvcConfig`、`CorsConfig`：Bearer Token 验证、Redis 白名单核对、最低角色拦截、请求结束清理上下文及浏览器插件来源预留。
- [ ] 完成 `/api/auth/register`、`/api/auth/login`、`/api/auth/me`、`/api/auth/logout`，验证未登录 401、权限不足 403、OWNER 向下兼容及高开销接口的角色门槛。

### Stage 1 前端主页与个人入口

- [ ] 在 `main.ts` 接入 Element Plus 样式及图标，完成响应式主页外壳。
- [ ] `toolRegistry.ts` 注册妙替 `/replace`、妙压 `/targetkb`，让导航和首页工具卡片由注册表渲染，并配置实际页面路由。
- [ ] 实现 `api/request.ts` 和 `api/auth.ts`：携带 `polyhouse_token`、统一响应处理、拦截 401。
- [ ] 实现 `useUserStore`：维护身份、`polyhouse_user`、会话恢复和退出清理。
- [ ] 完成 `TopNavBar`、`AuthDialog`、`UserPopoverCard`：登录注册弹窗、不跳转悬浮个人卡片、头像昵称、角色徽章、云端入口及退出操作。
- [ ] 完成注册、登录、刷新恢复、失效退出的前后端联调和浏览器验收。现有空 Vue 组件不计作该项完成。

### Stage 2 妙替 PolyReplace

- [ ] 明确重叠规则和匹配优先级，实现纯 TS AC 自动机及测试，保证整体匹配和无二次串改。
- [ ] 实现 Worker 消息协议、目录非递归有界并发 BFS、二进制过滤和 UTF-8/GB18030 编码处理。
- [ ] 实现保留目录树的 ZIP 导出及授权后的本地文件夹输出，验证文件不上传、中文不乱码和大目录处理。
- [ ] 实现替换规则编辑页面、用户云端模板接口与 `tool_replace_template` 数据隔离。

### Stage 3 妙压 TargetKB

- [ ] 实现 Worker 图片二分压缩、JPEG COM 无损增重、DPI 头重写及边界样例测试。
- [ ] 实现证件照换底、PDF 端侧压缩、Pointer Events 对比交互及实际文件兼容性验收。
- [ ] 实现官方预设和个人预设接口、缓存及 `tool_targetkb_preset/tool_user_preset` 映射，验证用户数据隔离。
- [ ] 验证目标体积区间、画质/尺寸/DPI、断网可用和零文件上传；性能目标以实测记录为准，不提前宣布达标。

### 发布前验收

- [ ] 完整业务主流程、真实数据库联调和用户数据隔离验收。
- [ ] 检查角色权限、敏感信息、依赖与配置、环境变量和构建可复现性。
- [ ] 核对全部已完成项的证据与未关闭问题，冻结 v1.0 发布范围并整理正式变更日志。
- [ ] 获得用户发布授权后再创建正式版本标签或发布记录；普通 Git 提交不自动产生正式版本。

### 后续扩展预留

- [ ] AI/高开销第三方接口及 OWNER 管理入口：目前只有角色设计和注解准备，未接入任何收费接口。
- [ ] 完整 Docker Compose 编排与迁移验证：根 `docker-compose.yml` 当前仅有空 `services`，不具备一键部署能力。
- [ ] URL Query 状态分享、浏览器插件/移动端接入等按后续需求推进，不自动列为 v1.0 发布门槛。

## 暂定迭代记录

以下为 2026-10-26 补记的实际工作，按最新进展在前排列；不代表这些变更已经正式发布。

### 2026-10-26 进度归档

- 将原来的任务提示改为已完成/未完成清单，保留 Stage 1 原始需求及策划书核心工具方向。
- 明确 README、工程约定、策划书、开发日志职责，避免重复约束和将占位文件误判为业务完成。
- 单列用户确认的 MySQL 与 IDEA 连接证据，后续正式日志可由本清单和 Git 历史整理，避免遗漏。

### 2026-10-26 Redis 接入与 IDEA 验收

- 复核 WSL Redis 运行及启用状态，沿用现有服务，未另建 Docker 实例。
- 完成 Spring Redis 配置、可读字符串模板、可选认证参数及显式实例测试，补充 README 连接步骤。
- 用户在 IDEA 中完成无认证数据源测试；项目侧通过真实 PING、读写、计数、TTL 和清理测试。

### 2026-10-26 公共层实现与复核完善

- 完成统一响应、角色枚举、权限声明、用户上下文、鉴权 Redis 常量、业务异常和全局异常处理。
- 复核后补齐 ErrorCode 与 HTTP 状态契约、MVC 方法校验、日志安全定位、SQL 参数日志关闭、实际 HTTP/JSON/线程隔离测试。
- 新增边界测试发现 406 错误体丢失，修复后回归通过；未提前实现登录、数据库业务或工具算法。

### 2026-10-26 工程初始化与 JDK 环境统一

- 建立单仓前后端目录和基础文件骨架、根 Git 忽略与换行规则，完成初始化提交及远端同步。
- 修正工程约定文件名与策划书引用，将原明文敏感配置改为环境变量。
- 定位 Maven 使用 JDK 25、终端使用 JDK 17 的旧进程环境差异；用户调整并重启 Codex 后，实际工具链统一为 JDK 21。

## 最近验证基线

下表记录文档整理前最近一次已实际执行的结果。后续获准更新日志时，用新结果替换，不把历史成功视为新代码的自动验收。

| 范围 | 命令或证据 | 已确认结果 |
| --- | --- | --- |
| 后端编译 | `mvn -q compile -DskipTests` | JDK 21 下通过 |
| 普通测试 | `mvn -q test` | 39 项通过，3 项 Redis 实例测试按设计跳过，0 失败/错误 |
| 含 Redis 的全量测试 | `mvn -q '-Dpolyhouse.redis.integration=true' test` | 42 项全部通过，0 失败/错误/跳过 |
| 前端 | `npm run build` | TypeScript 检查及生产构建通过 |
| 差异检查 | `git diff --check` | 通过 |
| 提交候选安全检查 | 配置敏感值、忽略目录、超过 10 MiB 文件 | 未发现明文敏感配置、构建产物混入或超限候选 |
| IDEA Redis | 用户提供的连接成功截图 | Redis 7.0.15，单机，无认证，数据库 0 |
| MySQL | 用户确认四张表已建立并能连接 | 仅确认本机环境；尚未验证应用 Mapper/业务查询 |

测试中的异常分支会产生预期错误级日志；测试是否失败以退出码和 Surefire 汇总为准。当前 Mapper 仍是骨架，启动时的未找到 Mapper 警告不应被解释为持久层已经实现。

## 正式发布记录

暂无正式发布。完成并验收 v1.0 后，将本文件中已核实的增量整理为发布条目，记录发布日期、实际交付范围、迁移说明、验证结果及对应 Git 标签；继续保留尚未完成的事项与必要历史证据。
