# P3 受控账号管理

本说明对应已批准计划的 P3，不表示已部署。权限以服务端实际角色和目标状态为准；生产传输仍需要 HTTPS。已有开户和首次改密流程见[开户说明](account-provisioning.md)。

## 页面与权限

- 个人菜单的 `/manage` 为账号管理页；OWNER 可进入原 `/manage/accounts` 创建账号。未改变原开户 URL、首页、工具或个人中心布局。
- ADMIN 只看到 USER 的必要资料和同范围计数，只能封禁 USER；INSIDER 不因等级较高而取得管理权。
- OWNER 可查看其他非 OWNER 账号和自己的只读行，封禁非 OWNER、解封和重置密码。禁止处置自身或 OWNER；不开放角色编辑、屋主移交或匿名密码找回。
- 每次动作先展示精确账号/编号/身份/状态，要求原因。重置任意账号和封禁 ADMIN 要求再次输入屋主当前密码。封禁保留资料与成长，重置保留封禁状态。

## HTTP 定义

全部需要 Bearer Token、完整首次改密状态及相应管理角色，正常响应为 `Result<VO>` 且 `Cache-Control: no-store`。

| 方法与路径 | 输入 | 行为 |
| --- | --- | --- |
| GET `/api/manage/accounts` | page 默认 1、pageSize 默认 20/最多 50、username 登录名前缀、role、status | 有界分页，固定编号倒序；ADMIN 始终仅 USER |
| POST `/api/manage/accounts/{id}/ban` | 下述管理意图 | ADMIN → USER；OWNER → 非 OWNER |
| POST `/api/manage/accounts/{id}/unban` | 同上 | 仅 OWNER，恢复登录资格，旧 Token 不复活 |
| POST `/api/manage/accounts/{id}/password/reset` | 同上及 ownerPassword | 仅 OWNER；一次随机临时密码，要求首次改密 |
| GET `/api/manage/accounts/operations/confirmation` | requestId 小写 UUID | 仅核对当前操作者成功记录，绝不返回密码 |

管理意图字段为 requestId、expectedVersion（列表 version 字符串）、expectedRole、expectedStatus、reason（1～200 字，不含控制字符）、条件性的 ownerPassword。目标只从 URI 读取，`?id=` 不能覆盖。账号编号和版本以字符串返回，避免 JavaScript 整数精度丢失；分页页码上限 10000，并非无限全量导出。

400 表示输入/再次验密不符，401 表示登录已失效，403 表示角色/目标不允许或必须首次改密，404 表示目标/成功记录不可用，409 表示意图冲突或列表已过期，503 表示存储服务不可用。核对 404 也可能原请求尚未完成，不等于可直接重发。

## 并发、审计与故障边界

`AccountManagementService` 按用户编号升序锁住操作者和目标，再核对最新身份、凭据版本和 Redis 会话。页面携带的管理版本、目标身份、状态必须吻合；另一动作已完成则返回 409，不用旧列表覆盖新状态。`management_version` 仅 P3 管理动作递增；本人改密使用已有 `credential_version`，不把管理版本宣称为所有资料变化的版本。

定向更新只改变启停状态、管理/凭据版本及重置时的密码哈希/首次标记，不回写昵称、头像、经验、硬币等整行旧资料。每个成功动作都推进凭据版本并删除 PC/H5/PLUGIN 会话，解封不会降低版本或恢复键值。所有服务器节点必须保留 P1 凭据版本校验。

成功审计与幂等事实共用 `sys_account_management_operation`，主键为操作者/请求号。记录目标、角色/状态/版本快照、动作、最小原因、时间，不存密码/Token/完整请求。相同请求并发只执行一次，重放和查询均不再返回临时密码。审计查询页和保留期限自动清理尚未实现；为防止旧意图重放，当前不随意删除事实记录。

MySQL 更新与事实记录同事务；审计写入失败全部回滚。Redis 不属于 MySQL 事务：删除前失败，数据库回滚；删除已经生效再失败，数据库回滚但不恢复旧会话，用户可能需要重新登录。这是保守的失败处理，不承诺两个存储绝对原子提交。

前端不自动重发写请求。仅在 sessionStorage 保留操作者/请求号/目标/动作摘要，不保存原因或任何密码；刷新后只核对原请求。一次临时密码仅在页面内存，关闭/换号/退出/卸载后清除。未保存就无法恢复，先结束核对、加载最新列表，再由屋主再次验密并明确发起新重置；不能静默生成第二个密码。复制交付会使用系统剪贴板，请用私密渠道交付。

## 迁移与回退

备份后，在已完成 001～006 的数据库人工执行 [007_account_management.sql](../../polyhouse/sql/007_account_management.sql)，再启动新版。追加管理版本、有界查询索引和成功事实表，不修改现存账号/角色/密码/成长，不在启动时自动改表；重跑不清零管理版本或事实。DDL 可能隐式提交，同名不兼容结构需人工检查，不用 `IF NOT EXISTS` 代替结构验收。

优先关闭管理写入口并修复。必要代码回退可使用兼容 P2 基线，保留 P1 的凭据版本/首次改密与公共注册关闭，保留 007 数据、成功事实及最新凭据版本；不降级凭据版本、不恢复旧 Token、不删除审计或反向自动解封。不得直接回到重新开放注册的 P1 或更早版本。整库恢复需单独确认、备份并撤销相关会话；Git 回退不恢复数据库。

## 验收入口

普通验收仍为根目录 `polyhouse/scripts/Test-Project.ps1`，显式关闭实例开关；真实管理验收：

```powershell
.\polyhouse\scripts\Ensure-LocalRedis.ps1
.\polyhouse\scripts\Invoke-LocalAuth.ps1 -Action Verify -AccountManagementOnly
```

该入口复用 Git 忽略的个人预设，限定本机 MySQL/Redis，随机建 `polyhouse_management_*` 测试库并迁移 001～007。仅在随机库创建四角色夹具，测试后删除该库和对应会话，不修改开发库正式屋主。三种隔离选项互斥，不能在正式开发库运行历史全量 OWNER 夹具。

可选浏览器：先启动本机 `127.0.0.1:5173` 前端和 Edge，设 `POLYHOUSE_MANAGEMENT_BROWSER=true`；若 Playwright 不在标准模块路径，以 `POLYHOUSE_PLAYWRIGHT_MODULE` 指向已有模块，不自动安装。测试启动独立无头 Edge，只代理根 `/api/` 到随机测试后端，不继承真实数据库/JWT 变量，不使用个人浏览器；七档宽度检查和密码遮罩截图仅保留在忽略的 target。实际结果见[当前阶段日志](../dev-log/v1.0-dev.7.md)。

下一步 P4 为昵称/头像审核，当前头像仍上传即发布；P3 完成不表示已满足受控内测上线条件，尚未操作朋友服务器。
