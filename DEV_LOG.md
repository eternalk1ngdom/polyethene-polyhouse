请先完整阅读当前根目录下的 DEV_LOG.md（及项目策划文档），严格遵守其中的全局架构契约、包路径规范（com.polyethene.polyhouse）与前后端目录结构标准。

目前我已经手动完成了：
1. 后端 ./polyhouse 的 Maven pom.xml 与 application.yml 装载配置；
2. 前端 ./polyhouse-web 的 Vite + Vue3 + TS + Pinia + Element Plus 初始化与 vite.config.ts 代理配置；
3. 本地 MySQL 数据库 polyhouse_db 已创建好 sys_user、tool_replace_template、tool_targetkb_preset、tool_user_preset 四张表。

现在请你严格按照策划书规范，自动创建前后端缺失的所有业务子文件夹（包结构），并完成 Stage 1（企业级鉴权底座与前端主页外壳）的全部代码实现：

【后端任务 (./polyhouse/src/main/java/com/polyethene/polyhouse/)】
1. 创建完整包结构：common/{annotation,constant,context,exception,result}、config、controller、interceptor、mapper、model/{dto,entity,vo}、service/impl、utils。
2. 实现通用基础层：Result<T> 统一响应体、ErrorCode、UserRoleEnum（USER=10, INSIDER=50, OWNER=99 三阶权限枚举）、@RequireRole 注解、UserContext (ThreadLocal 存 userId 与 role)、BusinessException 与 GlobalExceptionHandler。
3. 实现无状态工具类：PasswordUtils (封装 Hutool BCrypt) 与 JwtUtils (HMAC-SHA256)。
4. 实现 AuthInterceptor（校验 Bearer Token + 核对 Redis 白名单 polyhouse:auth:token:{userId}:{clientType} + 校验 @RequireRole 权限权重）及 WebMvcConfig、CorsConfig（预留放行 chrome-extension://*）。
5. 实现 sys_user 对应的 User 实体、RegisterDTO、LoginDTO、UserProfileVO、UserMapper 以及 AuthServiceImpl（含 Redis 15分钟连错5次防暴破锁定 polyhouse:auth:fail:{username}）与 AuthController（/api/auth/register, /api/auth/login, /api/auth/me, /api/auth/logout）。

【前端任务 (./polyhouse-web/src/)】
1. 创建完整目录：config/toolRegistry.ts（注册一号模块“妙替 /replace”与二号模块“妙压 /targetkb”）、core/、workers/、api/、stores/、components/、views/。
2. 在 main.ts 中引入 Element Plus 样式与图标，封装 src/api/request.ts（自动携带 localStorage 中的 polyhouse_token 并拦截 401）及 src/api/auth.ts。
3. 实现 Pinia 状态管理 src/stores/useUserStore.ts。
4. 实现顶部导航栏 TopNavBar.vue（基于 toolRegistry.ts 动态渲染菜单）、未登录模态框 AuthDialog.vue、以及已登录右上角 B站风格不跳转悬浮卡片 UserPopoverCard.vue（展示头像、昵称、UserRole 身份徽章及退出登录）。
5. 配置路由并在完成后执行 mvn test-compile 与 npm run type-check 验证前后端零编译错误，最后将本次开发记录更新至根目录 DEV_LOG.md 的 [v0.2.0] 节点中。