# P4 昵称与头像审核

本说明对应受控内测计划 P4；实际验收记录见 [v1.0-dev.8](../dev-log/v1.0-dev.8.md)。云端验收状态见[上线清单](../dev-log/launch-preparation.md)。昵称与头像先提交、后审核、再发布；屋主自己的资料也要显式批准。

## 页面与接口

个人中心 `/account` 保留裁剪器和已公开资料，在账号资料卡中增加昵称表单、近期申请、剩余额度、待审头像的私有预览。屋主从 `/manage` 进入 `/manage/reviews`，管理员与 INSIDER 没有审核权限。新页面不更换首页、工具、全站配色或现有账号管理布局。

待审头像只有「预览」入口。图片加载成功后可通过；驳回时必须填写原因，通过留空时以「通过头像审核」作为审计说明。右上角 × 或 Esc 可关闭预览。

| 方法和地址 | 权限与行为 |
| --- | --- |
| POST `/api/auth/avatar/submissions` | 本人完整会话；JSON 的 avatarBase64 与小写 UUID requestId；标准 Base64 最长 60,000 字符，只接受 160×160、20 KiB 以内的静态 WebP |
| 原 POST `/api/auth/avatar` | 新后端明确关闭，合法完整身份请求返回 409；不调用上传或发布服务 |
| POST `/api/auth/nickname/submissions` | 本人完整会话；requestId/nickname；昵称 1～32 字符，不含控制/格式/换行字符，按纯文本显示 |
| GET `/api/auth/profile-submissions` | 本人最近十条记录与两种当日额度，不接受他人用户编号 |
| GET `/api/profile-submissions/{id}/image` | 仅有效待审图的本人/OWNER；返回 WebP 字节，no-store、nosniff；终态、过期和账号安全版本不符均不提供图片 |
| GET `/api/manage/profile-reviews` | 仅 OWNER；page/pageSize（默认 20、最多 50）/kind；仅有效待审申请及同条件计数 |
| POST `/api/manage/profile-reviews/{id}/decision` | 仅 OWNER；expectedVersion/approve/reason；批准或驳回精确版本，不能替换申请内容 |
| GET `/api/manage/profile-published` | 仅 OWNER；page/pageSize/kind/unverifiedOnly；清点历史与已发布内容，不返回完整用户实体 |
| POST `/api/manage/accounts/{id}/profile/takedown` | 仅 OWNER；kind/expectedVersion/expectedValue/action/reason；action 为 KEEP 确认保留历史，或 TAKEDOWN 下架 |

除图片外均为 `Result<VO>`；正常私有 JSON 和图片均 no-store。编号/版本以字符串返回，待审 Base64、磁盘路径和内容指纹不进入 VO。路径 DTO 转换器防止同名查询 id 覆盖 URI。400 输入不符，401 会话失效，403 角色/首次改密受限，404 资源不可用，409 版本/状态/意图冲突，429 当日次数用完，503 存储暂不可用。

头像上传特意使用新地址：新页面请求旧后端会 404，旧页面请求新后端被关闭，避免旧后端先公开后才被新前端识别为错误响应。上线/回退时仍必须停止旧应用的写入口，不能同时运行允许立即发布头像的旧服务。

## 状态、计次与并发

- 状态为 PENDING、APPROVED、REJECTED、SUPERSEDED、EXPIRED、INVALIDATED。每人每字段最多一份有效待审，新提交将同类旧待审标为 SUPERSEDED；只有有效 PENDING 能被审核，旧版本或终态返回 409。
- 上海自然日内头像最多提交 5 次、昵称 2 次。成功提交入库即计次，驳回/替代不返还；事务回滚和非法图片不计次。Redis 在事务提交后记录次数，TTL 为 24 小时；缓存不可用时以数据库成功记录为准。升级当天叠加旧 ext_json 中的头像次数，不修改其他设置，不借迁移清零配额。
- 相同用户/requestId 与同种内容指纹重复提交返回原申请，不多写图、不多扣额度；复用编号提交不同内容/字段返回 409。前端不自动重发 POST，明确点击重试原内容沿用编号；刷新先看申请状态，改变内容视为新申请。
- `ProfileModerationService` 按用户编号升序取得操作者/目标用户锁，再读取字段与申请最新状态。昵称/头像拥有独立版本，只对单个字段定向更新，不回写成长、角色或整份旧 User。
- 申请保存提交时的 credential_version。封禁、解封或重置密码后，旧申请即使后台尚未清理也不能批准；本人改密同样使旧待审失效，需要重新提交。账号注销/停用后不能提交，失效账号的旧待审不能发布。
- 下架时同时推进该字段版本、使旧待审失效；头像恢复 null 默认，昵称恢复系统默认。`ph` 序号账号沿用“小屋住户+序号”，历史自定义账号采用“小屋住户+用户编号”，不改登录账号。历史 APPROVED 事实不改写成驳回或下架。
- 原资料和历史文件不自动算已审核。清单默认显示未核对内容，由屋主明确 KEEP 或下架，操作都有审计；迁移不批量重置真实屋主。新系统默认昵称也可由屋主在此统一核对，不自动猜测某个旧字符串是否经过审批。

## 图片存储与回退

- `sys_user.avatar` 和 `pending_avatar` 分别存生效及唯一待审的 Base64。`avatar_status` 为 0（无待审）、1（待审）或 2（驳回）。完整申请状态、版本和审核结果仍在审核表；会话失效、过期等以申请有效性校验为准。
- 只有 OWNER 批准才把待审字节复制到生效字段。驳回、替代、下架或失效清空待审字节，申请记录与最小审计保留。待审内容有效七天，维护任务每轮最多处理 32 条失效申请。头像公开资料返回固定 WebP data URL；待审图片仅由本人和 OWNER 经 Bearer 获取，无共享缓存，预览对象 URL 随切换或卸载释放。
- 已有库先停写并备份，按顺序执行 [008](../../polyhouse/sql/008_profile_moderation.sql) 与 [009](../../polyhouse/sql/009_avatar_database.sql) 中未执行的迁移。旧公开路径保留供离线核对，界面回退默认头像；旧磁盘待审作废，由用户重新提交。旧文件如需迁移须另行验收，发布过程不会读取或删除它们。
- 前后端一起切换 JSON 契约。回退先停止写入，使用与旧应用兼容的数据库及媒体备份并核对新提交影响。切 Git 版本无法恢复数据，禁止回退到允许头像直接发布的接口。

## 验证入口

普通验收为 `polyhouse/scripts/Test-Project.ps1`。真实 P4 验收复用 Git 忽略的 IDEA 私有预设，不重新索取或输出密码：

```powershell
.\polyhouse\scripts\Ensure-LocalRedis.ps1
.\polyhouse\scripts\Invoke-LocalAuth.ps1 -Action Verify -ProfileModerationOnly
```

该选项与其他三个专项隔离选项互斥。仅在随机 `polyhouse_profile_*` 库迁移 001～009，使用本次测试夹具；不会用真实 OWNER 或头像做夹具。结束精确清理会话、计数键、库和测试产物。

可选真实浏览器：前端 `127.0.0.1:5173` 在线、已安装 Edge，设置 `POLYHOUSE_PROFILE_BROWSER=true`；Playwright 位于非标准路径时使用 `POLYHOUSE_PLAYWRIGHT_MODULE` 指向已有模块，不自动安装依赖。独立无头 Edge 只代理根 `/api/` 到随机后端，不继承真实数据库/JWT 凭据，不操作个人浏览器。截图、安全摘要仅保存在忽略的 target。

## U4 当前页面组合验收（受控浏览器）

现有自动化脚本入口保留；使用 CUA 时采用下列专用入口，不需要脚本自行启动或控制浏览器。在一个终端从 `polyhouse-web/` 执行 `npm run dev -- --host 127.0.0.1 --port 5175 --strictPort`，另一个终端从仓库根目录执行：

```powershell
.\polyhouse\scripts\Ensure-LocalRedis.ps1
.\polyhouse\scripts\Invoke-LocalAuth.ps1 -Action Verify -ProfileModerationOnly -WorkspaceBrowser
```

仅选中 `ProfileModerationIntegrationTest#interactiveWorkspaceRegression`。随机隔离库、独立 JWT 与测试产物目录仍由原 Sandbox 管理；临时桥接只监听本机 5190，把实际前端请求转发到本次随机 Spring Boot 端口，禁止指向 8080，不模拟业务响应、不接入开发库。入口将真实测试登录响应交给隔离来源页面，令牌不显示在界面、日志或 URL；凭据文件只在忽略的 `target/p4-*` 内短暂存在，子进程不继承数据库密码或开发密钥。生产 `main.ts` 不导入该测试入口。

启动后用受控浏览器访问 `http://localhost:5190/account`。顶部测试控件可切换本次 OWNER/ADMIN/USER/INSIDER/首次改密会话，并模拟离线、恢复延迟或“真实写入已处理但响应传输不完整”；统计仅含方法、路径、状态，不含请求体或令牌。桥接拒绝陌生 Host/Origin。依次检查个人分类/草稿、昵称头像先审后发、屋主审核/历史处置、开户与凭据离页清理、管理员封禁/屋主解封及未知结果核对。真实密码重置/本人改密的 HTTP 与撤销行为由原隔离测试验证；浏览器只检查相关表单和再验密入口，遵守新凭据输入交接要求。

完成后点击“结束隔离验收”；Java 再核对已批准/驳回申请、恰好一次开户/两笔管理事实、用户最终状态与唯一屋主，并核对恰好一次封禁 POST、一次解封 POST 和原请求 GET 确认，不能仅凭浏览器结束按钮算通过。故障模拟先发送响应头再截断实体，避免直接 reset 空连接被浏览器透明重放，从而漏验“结果未知”分支；应用写请求和数据库幂等仍各自承担原职责。

测试最多保留 25 分钟，正常结束或测试失败均清理桥接/临时入口、会话、库和图片；另行关闭本次 5175 Vite。测试入口删除会触发开发热更新，宜先完成截图和页面检查；关闭失效测试标签即可。若进程被外部强制终止，先只读核对本次随机前缀残留，再定向恢复清理，禁止清空开发库或批量删除其他夹具。
