# P4 昵称与头像审核

本说明对应受控内测计划 P4；实际验收记录见 [v1.0-dev.8](../dev-log/v1.0-dev.8.md)。仅本机开发，尚未部署朋友服务器。昵称与头像先提交、后审核、再发布；屋主自己的资料也要显式批准。

## 页面与接口

个人中心 `/account` 保留裁剪器和已公开资料，在账号资料卡中增加昵称表单、近期申请、剩余额度、待审头像的私有预览。屋主从 `/manage` 进入 `/manage/reviews`，管理员与 INSIDER 没有审核权限。新页面不更换首页、工具、全站配色或现有账号管理布局。

| 方法和地址 | 权限与行为 |
| --- | --- |
| POST `/api/auth/avatar/submissions` | 本人完整会话；multipart 的 file 与小写 UUID requestId；只接收裁剪后的 256×256 JPEG/PNG，文件上限 256KiB，重新编码成 JPEG |
| 原 POST `/api/auth/avatar` | 新后端明确关闭，合法完整身份请求返回 409；不调用上传或发布服务 |
| POST `/api/auth/nickname/submissions` | 本人完整会话；requestId/nickname；昵称 1～32 字符，不含控制/格式/换行字符，按纯文本显示 |
| GET `/api/auth/profile-submissions` | 本人最近十条记录与两种当日额度，不接受他人用户编号 |
| GET `/api/profile-submissions/{id}/image` | 仅有效待审图的本人/OWNER；返回 JPEG 字节，no-store、nosniff；终态、过期和账号安全版本不符均不提供图片 |
| GET `/api/manage/profile-reviews` | 仅 OWNER；page/pageSize（默认 20、最多 50）/kind；仅有效待审申请及同条件计数 |
| POST `/api/manage/profile-reviews/{id}/decision` | 仅 OWNER；expectedVersion/approve/reason；批准或驳回精确版本，不能替换申请内容 |
| GET `/api/manage/profile-published` | 仅 OWNER；page/pageSize/kind/unverifiedOnly；清点历史与已发布内容，不返回完整用户实体 |
| POST `/api/manage/accounts/{id}/profile/takedown` | 仅 OWNER；kind/expectedVersion/expectedValue/action/reason；action 为 KEEP 确认保留历史，或 TAKEDOWN 下架 |

除图片外均为 `Result<VO>`；正常私有 JSON 和图片均 no-store。编号/版本以字符串返回，私有键、磁盘路径和内容指纹不进入 VO。路径 DTO 转换器防止同名查询 id 覆盖 URI。400 输入不符，401 会话失效，403 角色/首次改密受限，404 资源不可用，409 版本/状态/意图冲突，429 当日次数用完，503 存储暂不可用。

头像上传特意使用新地址：新页面请求旧后端会 404，旧页面请求新后端被关闭，避免旧后端先公开后才被新前端识别为错误响应。上线/回退时仍必须停止旧应用的写入口，不能同时运行允许立即发布头像的旧服务。

## 状态、计次与并发

- 状态为 PENDING、APPROVED、REJECTED、SUPERSEDED、EXPIRED、INVALIDATED。每人每字段最多一份有效待审，新提交将同类旧待审标为 SUPERSEDED；只有有效 PENDING 能被审核，旧版本或终态返回 409。
- 上海自然日内头像最多提交 5 次、昵称 2 次。成功提交入库即计次，驳回/替代不返还；事务回滚、非法图片或保存失败不计次。升级当天叠加旧 ext_json 中的头像次数，不修改其他设置，不借迁移清零配额。
- 相同用户/requestId 与同种内容指纹重复提交返回原申请，不多写图、不多扣额度；复用编号提交不同内容/字段返回 409。前端不自动重发 POST，明确点击重试原内容沿用编号；刷新先看申请状态，改变内容视为新申请。
- `ProfileModerationService` 按用户编号升序取得操作者/目标用户锁，再读取字段与申请最新状态。昵称/头像拥有独立版本，只对单个字段定向更新，不回写成长、角色或整份旧 User。
- 申请保存提交时的 credential_version。封禁、解封或重置密码后，旧申请即使后台尚未清理也不能批准；本人改密同样使旧待审失效，需要重新提交。账号注销/停用后不能提交，失效账号的旧待审不能发布。
- 下架时同时推进该字段版本、使旧待审失效；头像恢复 null 默认，昵称恢复系统默认。`ph` 序号账号沿用“小屋住户+序号”，历史自定义账号采用“小屋住户+用户编号”，不改登录账号。历史 APPROVED 事实不改写成驳回或下架。
- 原资料和历史文件不自动算已审核。清单默认显示未核对内容，由屋主明确 KEEP 或下架，操作都有审计；迁移不批量重置真实屋主。新系统默认昵称也可由屋主在此统一核对，不自动猜测某个旧字符串是否经过审批。

## 图片落盘、清理和故障

公开目录沿用 `POLYHOUSE_AVATAR_DIRECTORY`，本机为 `E:\polyhouse-data\avatars`。待审目录从该配置推导为同级 `E:\polyhouse-data\avatars-pending`，不在源码/C 盘临时目录，不注册公开静态映射。图片字节不进 MySQL、Redis 或 localStorage。私有 URL 必须带 Bearer 请求获取 Blob，组件只生成临时对象 URL，换图/换号/卸载即撤销。

头像通过按钮需等待当前私有图片真实解码成功，旧图片事件不能误放行新目标；服务端仍独立校验图片和版本。

单个私有文件最多 256KiB，单机存储最多 512 个服务生成的图键（理论约 128MiB）；达到上限拒绝新头像，先处理/清理旧申请。该目录上限在单 JVM 内串行控制，当前仅支持单机单实例；多实例/OSS 需另行设计共享配额。用户原图仍在浏览器裁剪，服务器不保留原图。

待审有效七天。每分钟限量处理 32 条过期/账号安全状态不符记录，移除文件引用，提交后删除；驳回、替代和下架也在成功提交后清理私有图。删除失败保存 `.cleanup` 标记并限量重试；扫描最多 512 个键，对照数据库回收超过 24 小时的无引用私有孤儿。数据库不可用时不擅自删除。拒绝路径穿越、符号链接、目录文件和超限读取；文件名由服务端随机生成。

申请/状态/公开字段/最小审计同一 MySQL 事务。文件不属于数据库事务：回滚删除新私有图或新公开图，保留旧图和原待审；成功提交才删除被替代图。UNKNOWN 可能已提交，不做破坏性补偿；私有孤儿在宽限后核对回收。极端的公开文件提交未知/清理失败需备份后人工对照公开引用处理，不能按“未收到成功响应”直接删文件。已公开文件只在没有任何用户引用时清理；已下载/缓存的副本无法保证立即撤回。

## 迁移、备份与兼容回退

在已执行 001～007 的库，先备份数据库、公开头像目录、已有私有待审目录，再人工执行 [008_profile_moderation.sql](../../polyhouse/sql/008_profile_moderation.sql)，最后重启后端。迁移仅追加 `sys_profile_field`、`sys_profile_submission`、`sys_profile_audit`；字段状态懒创建，不自动回填已审标记或改用户资料。同名不兼容表定义须先人工核对，IF NOT EXISTS 不代表结构一致。清理引用使用 private_key/status 索引，已先执行的同阶段库可幂等补齐；重跑不重置版本、记录或次数；DDL 可能隐式提交，不能省略备份。

先暂停写入口，优先兼容修复。若回到 P3，必须同时封闭旧 POST `/api/auth/avatar`，否则重新出现未经审核直接发布；单纯切 Git 版本不安全。保留 008 表、版本、审计、私有目录和 P1 会话规则，不删除记录或把旧待审重新置为有效。整库恢复与媒体目录恢复必须使用同一份备份并独立确认；Git 回退不恢复数据库/图片，不能随意覆盖现在的真实用户内容。

历史内容需要你登录屋主页面逐项核对；功能验收通过不表示真实历史内容全部已审。P5 再做部署、加密访问、备份恢复和合并验收。

## 验证入口

普通验收为 `polyhouse/scripts/Test-Project.ps1`。真实 P4 验收复用 Git 忽略的 IDEA 私有预设，不重新索取或输出密码：

```powershell
.\polyhouse\scripts\Ensure-LocalRedis.ps1
.\polyhouse\scripts\Invoke-LocalAuth.ps1 -Action Verify -ProfileModerationOnly
```

该选项与其他三个专项隔离选项互斥。仅在随机 `polyhouse_profile_*` 库迁移 001～008，在本次 target/p4-* 目录保存测试图片；不会用真实 OWNER 或头像做夹具。结束精确清理会话/库/本次图片目录。

可选真实浏览器：前端 `127.0.0.1:5173` 在线、已安装 Edge，设置 `POLYHOUSE_PROFILE_BROWSER=true`；Playwright 位于非标准路径时使用 `POLYHOUSE_PLAYWRIGHT_MODULE` 指向已有模块，不自动安装依赖。独立无头 Edge 只代理根 `/api/` 到随机后端，不继承真实数据库/JWT 凭据，不操作个人浏览器。截图、安全摘要仅保存在忽略的 target。
