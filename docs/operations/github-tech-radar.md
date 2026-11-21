# 技术雷达后端运行与验收

星潮的真实前后端浏览器联调已通过。本机开发库已完成增量迁移，云端调度待验。DeepSeek 真实样本及账单见[G4 验收记录](trend-g4-acceptance.md)。公开查询只读取已发布的日报。

## 本机启动与首次阅读

屋主在发布台看到的是该任务实际发布版本的结果。新生成、有效复用、沿用旧稿分别统计；输出被拒后如果还有旧稿，同一项目也会出现在已记录的拒收数中。详情页的“本期核对”表示本次观察的资料版本和有效复用条件匹配，旁边的生成时间属于原稿。历史日报缺少未生成原因时仅按当时已保存的信息显示。

已有星潮表的本机直接用 IDEA 的 `PolyhouseApplication` 运行后端，前端照常用 `npm run dev`。未指定 Spring profile 时，默认可以阅读和手动更新。原 Redis 启动检查和数据库连接继续复用。

需要同时启动两端时，在仓库根目录运行 `.\polyhouse\scripts\Start-LocalStarTide.ps1`。入口复用相同的数据库和 JWT 私有预设，组合启动前端 5173、后端 8080。遇到 WSL 故障时，Docker Desktop 在线也可使用以下方式：

```powershell
.\polyhouse\scripts\Start-LocalStarTide.ps1 -DockerRedis
```

该选项在本机缺少可用 Redis 时才用已有 `redis:7-alpine` 启动临时容器，绑定本机 6379。入口可复用已运行的聚妙屋 Vite 前端，结束时清理自己创建的前端和临时 Redis。可在 IDEA 的 External Tools 添加同一命令，保留原启动脚本与启动前任务。

使用终端启动时，结束后按 `Ctrl+C`。若 Maven 批处理询问 `Terminate batch job (Y/N)?`，输入 `Y` 结束并等待清理。

看到 `Tomcat started` 后，打开 `http://127.0.0.1:5173/polyethene-polyhouse/#/trend`。用原屋主登录，从日报进入「星潮发布台」，点「更新本期日报」。第一次由真实 GitHub 数据生成基础日报，任务显示「已发布，部分解读待补充」后即可阅读。此入口关闭 AI、每日定时和内容缓存，首次手动更新无 AI 费用。G4 的历史样本及账本留在验收库。

## 隔离浏览器专项

前端依赖、Edge 和 Playwright 可用时，在仓库根目录运行以下命令。Playwright 已安装在非标准路径时，可通过本次环境变量 `POLYHOUSE_PLAYWRIGHT_MODULE` 指定。

```powershell
.\polyhouse\scripts\Invoke-LocalAuth.ps1 -Action Verify -TrendBrowserOnly
# 加上此参数才访问实时公开 GitHub，仍关闭 AI。
.\polyhouse\scripts\Invoke-LocalAuth.ps1 -Action Verify -TrendBrowserOnly -TrendBrowserLiveGithub
```

专项使用真实 Vite、Spring Boot、随机 MySQL 和临时 Redis，覆盖屋主登录和发布、任务恢复、历史及筛选、失败保留与匿名读。结束时回收随机库和 Redis，脱敏诊断位于 `polyhouse/target/trend-browser-result.log`。

## 启用与数据来源

- 本机默认 profile 启用模块及屋主任务，可显式传入 `POLYHOUSE_TREND_ENABLED=false` 或 `POLYHOUSE_TREND_JOBS_ENABLED=false` 临时关闭。新开发库需要先执行下述迁移。读取在模块关闭或报告表未就绪时返回 503，已迁移的空库返回空期次列表和日报 404。
- 生产 `prod` 继续默认关闭。完成迁移后可显式设置 `POLYHOUSE_TREND_ENABLED=true`，需要手动更新时再设置 `POLYHOUSE_TREND_JOBS_ENABLED=true`。可选 `POLYHOUSE_GITHUB_TOKEN` 只通过私有环境变量注入。
- AI 和每日定时独立关闭，手动任务可发布基础日报。关闭付费生成时仍按稳定仓库 ID 读取本地成功稿；能确认有效的标记为 `REUSED`，无法确认的保留原文与原分析时间并标记为 `STALE`。该读取不产生费用。使用 `Invoke-TrendAiLocal.ps1 -Action Run -EnableAi` 后，任务执行仍需显式开启，详见[安全输入说明](deepseek-key-safety.md)。
- 任务开关启用后，重启可继续已经受理且未过期的QUEUED任务，届时可能产生账本允许的付费；先核对已有任务及费用状态。关闭开关不会自动迁移、退款或开启每日调度。

## 定位一次更新的缺文原因

在后端运行日志中按任务编号查「星潮采集汇总」和「星潮资料故障」。汇总给出首个失败仓库、环节、故障数和整仓跳过数；单项记录给出已知状态码和限流等待截止时间（UTC）。日榜本身失败时直接查 `LISTING` 故障行。按相同编号查「星潮资料降级」可核对最终使用旧稿、来源简介还是仅有排名。只保留这些脱敏行即可复盘，IDEA 控制台的输出需在关闭或清屏前保存。旧日报没有这些采集诊断。

`README_NOT_FOUND` 表示公开身份通过核验后 README 接口返回 404。`UNVERIFIED_REDIRECT` 表示材料转址后无法确认属于刚核实的仓库和资源。`SKIPPED_AFTER_PAUSE` 表示本轮已暂停、该仓库没有发起请求。`COOLDOWN` 表示同一进程仍在等待上游恢复。等待期限取自有效的 `Retry-After` 或配额耗尽时的 `X-RateLimit-Reset`；已确认限流而无有效等待信息时至少等待一分钟。到期后由下一次获准任务再试。[GitHub REST API 请求建议](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api)

## 查询契约

| GET 路径 | 参数 | 行为 |
| --- | --- | --- |
| `/api/trend/periods` | `periodType=DAILY` 可省略；`limit` 默认 14、范围 1～30；可选 `before=YYYY-MM-DD` | 倒序 `items` 日期及排他 `nextBefore`，空库为空列表 |
| `/api/trend/report` | `periodType=DAILY` 可省略；可选 `periodKey=YYYY-MM-DD` | 未指定日期读最新已发布日报；指定日期读当日当前版本 |

均为匿名只读，统一 `Result<T>`，不恢复账号会话。无报告 404，非法日期/周期/限额 400，关闭或存储故障 503；超限 429 并带 `Retry-After`。沿用 no-store，不为公开内容改公共鉴权。

版本包含 UUID、北京期号、UTC ISO 采集/发布时间、来源和来源排名。仓库ID为字符串，未知为null，正文/错误/凭据不公开。G1旧报告缺少`insight`仍可读取；新报告可有结构化解读及生成时间，`ANALYZED`/`REUSED`表示有效，`STALE`明确沿用旧解读，其他状态说明缺失。所有需分析项目都有效时`insightComplete=true`，资源项目无需分析。未来前端须转义展示。

## 迁移与回退

1. 保持模块关闭，备份目标库，执行 `SELECT DATABASE()` 确认目标，盘点模块两表是否已经存在；不重放账号初始化。原 13 张基线业务表及 OWNER 保留。
2. 明确两表都不存在后，在选中的库执行 [010 迁移](../../polyhouse/sql/010_trend_reports.sql)。脚本无 USE，不自动运行；全新环境先按[基线初始化](database-bootstrap.md)，再追加本迁移。
3. 核对 JSON 对象 CHECK、索引、日期/版本组合外键。TiDB 须复核实际约束启用及兼容性；本轮仅在本机 MySQL 隔离库实测。
4. DDL 非整份脚本事务。失败先用 `SHOW CREATE TABLE` 盘点部分建表，不以 IF NOT EXISTS 掩盖不一致，不盲目重跑。
5. 回退先关闭模块并保留两表备份；旧应用可继续运行而暂留模块表。确需移除时，确认目标和数据可恢复后先删除 `tool_trend_daily`，再删除 `tool_trend_report_version`，不触及账号表。真实库删除或云端迁移须具体授权，本轮未执行。

如启用任务，在010之后先执行[011费用迁移](../../polyhouse/sql/011_trend_ai.sql)，再执行[012任务迁移](../../polyhouse/sql/012_trend_jobs.sql)。即使AI关闭，任务容量保护也会检查费用表。检查单槽仅有`singleton_id=1`一行、状态CHECK及版本/活动任务外键。没有自动补建单槽，缺失时拒绝受理。

任务回退先停用并停止执行器，等待外部请求结束，核对待执行任务及费用预留。可以暂留两表供排查；确需删除并已确认数据恢复方案后，先`tool_trend_job_slot`后`tool_trend_job`，保留010/011。任务表引用报告版本，完整回退必须先处理012，再处理010；费用事实不因任务回退删除。TiDB 迁移和真实库回退未执行。

使用费用接口还需在上述迁移后追加[013保守关闭审计](../../polyhouse/sql/013_trend_ai_resolution.sql)，核对请求唯一键、原月份/请求外键、金额/原因CHECK和月份索引。脚本不自动运行，不重写011，也不修改账号表。费用操作使用操作者→任务单槽→仓库→月份→请求的锁序，审计随同提交；原预留与审计不按任务保留期删除。

013回退优先停用相关接口并暂留审计表。确需移除须先备份且确认可恢复，再处理`tool_trend_ai_resolution`，保留011的已用金额和`SETTLED/MANUAL_RESERVED`事实。**回退不退款、不恢复预留、不重发AI**；重新启用费用接口前恢复原审计，不能只重建空表丢掉人工处理分类。完整移除模块时先处理013引用，再处理012/010及011，真实库删除仍须具体授权。本轮只在随机隔离库实测移除、恢复审计及不重复结算，未在开发/TiDB执行。

启用每日定时前，在010～013之后执行[014迁移](../../polyhouse/sql/014_trend_schedule.sql)。它只把任务操作者/凭据版本改成可同时为空，并追加日期/尝试受理表；新CHECK拒绝混合空身份，手动旧记录保持原值。系统任务没有用户身份，必须有匹配的受理映射及启用配置，绝不创建假OWNER。映射随超过90天的终结任务级联清理，报告/费用不删除；检查唯一键、外键、CHECK及实际启用状态。未启用定时的旧手动任务流程不访问新表。

014回退优先停用定时并保留表/身份快照；降级到不认识系统身份的旧程序前停用整个任务执行，公开报告可继续读。不要把NULL身份改为虚构用户；确需恢复012的非空列，必须先备份/导出系统任务和映射、明确授权处理相关行，确认无NULL后再移除映射及恢复约束。DDL不是整份脚本事务，部分失败先盘点，不能盲目重跑。隔离测试验证了只处理本测试系统行、保留手动记录/报告并恢复约束。TiDB 迁移和真实库回退未执行。

同日更新追加版本，行锁和短事务切换指针；较早采集不得覆盖较新版本。重试同一 UUID 不复制报告，也不把旧版本重新设为当前。

## 屋主受控任务

| 路径 | DTO | 返回与约束 |
| --- | --- | --- |
| `POST /api/manage/trend/jobs` | JSON `{"requestId":"客户端生成的UUID"}` | 202、`accepted/replayed/job`，不等待整批采集；只更新当前日榜，不接受来源URL、期号或角色 |
| `GET /api/manage/trend/jobs` | 查询参数`jobId=UUID` | 200、任务状态/阶段/时间/已发布reportId/固定原因码；无编号404 |

两项均需现有屋主Bearer会话，初始密码需先改密；其他三角色403。服务事务重新验证JWT、Redis会话及数据库最新屋主记录，不用权重替代精确管理授权。统一Result、no-store；非法UUID400，配置/存储未就绪503。管理入口30次/分钟、突发6、并发2，384KiB请求体；超限429/Retry-After，伪造转发头或共享地址不另开额度。生产还受既有连接和五秒读取等预算。

一次更新意图始终使用相同UUID；重复返回原事实，活动期间另一个UUID返回当前任务且`accepted=false`，没有再排一批。终结后新意图受60秒冷却。受理时冻结是否允许AI；打开AI但配置不完整时不受理新的AI任务。任务/Key开关分别控制，不自动打开。

状态QUEUED→RUNNING→SUCCEEDED/PARTIAL/FAILED/INTERRUPTED；阶段为QUEUED/COLLECTING/ANALYZING/PUBLISHING/DONE。`trigger=MANUAL/SCHEDULED`说明来源；系统任务编号可由屋主查询，但不能当作屋主手动受理UUID重放。reportId 表明已发布。需要解读的项目仍有缺文或旧稿待核实时为 PARTIAL，全部有当前有效解读时为 SUCCEEDED；关闭付费生成的任务也按此判断。失败保留最近阶段及固定原因，不返回内部异常。执行关闭时仍可由屋主查状态，数据库须已迁移。

从受理起最多30分钟，数据库UTC时钟；执行器每30秒维护，租约2分钟。模拟重建服务验证QUEUED可以领取；RUNNING过期标中断不重放。旧执行的心跳、发布和释放均校验槽与代数。每次外部调用前再查角色/凭据版本，改密、重置或禁用会停止后续调用；退出登录不撤销已经接受的后台意图。已经发送的请求不能保证远端取消，未知计费继续持有，不自动退款或重试。

最多1000任务，新受理时清理超过90天的终结任务；有任何费用RESERVED/UNKNOWN时保守保留所有任务上下文，达到容量拒绝受理。发布版本和费用不随清理删除。费用问题按下节受控处理，**不要手工清空账本或预留来恢复调用**。原执行器维护线程只负责领取/心跳；新定时检测线程见下文。

## 屋主费用查询与保守关闭

| 路径 | DTO与行为 |
| --- | --- |
| `GET /api/manage/trend/fees` | 可选`month=YYYY-MM`，省略为数据库时间对应的北京当前月；`limit`默认20、范围1～50；`after`为上页`nextAfter`，按原AI请求UUID升序排他翻页 |
| `POST /api/manage/trend/fees/resolutions` | 明确确认后保守关闭一笔原请求；首次成功和幂等重放均200，返回首次处理事实 |
| `GET /api/manage/trend/fees/resolution` | `resolutionId=UUID`确认处理是否已提交；不存在404，沿用原编号重试，不换号盲目处理 |

全部仅OWNER、初始密码需先改，服务事务再核对JWT/Redis/最新身份；统一Result/VO及no-store。与任务路由**共用**30次/分钟、突发6、并发2及384KiB上界，不能切换路由获得另一份额度。人工关闭属于受控管理，不发起新的AI/第三方付费调用，不提供预算重置、退款或解除月份暂停的入口。

查询返回的金额均为人民币微元（1元=1,000,000微元）：`accountedMicros`为已经计入本地预算的保守金额，`heldMicros`仍占用，`conservativeMicros`是已计入金额中的人工保守部分；月份余额与保守汇总在同条SQL快照中读取。`remainingMicros=max(0,当前配置上限-已计入-仍持有)`，不代表平台余额或允许立即调用。查看历史月份也使用当前配置上限解释余量，月份归属不变；限额下调后不会出现负余额。只读不建月、不修改预算、不触发AI。

`pending`仅为本页待核对，包含RESERVED/UNKNOWN的原AI请求编号、GitHub仓库ID、预留额及时间；**原AI请求编号与任务编号不同，也不是DeepSeek平台请求ID**。待核对列表可能包含正常执行中的请求，不能看到记录就立即关闭；分页是动态列表，处理后从第一页刷新。

处理顺序：先查看费用/任务状态并检查平台记录，确认原结果无法可靠恢复；确保无QUEUED/RUNNING采集任务，且原预留至少等待五分钟。以新`resolutionId`表达这一次处理意图，POST以下字段；示例编号需换成实际生成/查询所得UUID，不能粘贴Key或原始响应：

```json
{
  "resolutionId": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
  "requestId": "11111111-2222-4333-8444-555555555555",
  "expectedReservedMicros": 2800,
  "confirmation": "COUNT_RESERVED",
  "reason": "RESULT_UNRECOVERABLE"
}
```

`expectedReservedMicros`须与当前原预留相等；`confirmation`只能为`COUNT_RESERVED`；`reason`只允许`PROVIDER_RECORD_CHECKED`或`RESULT_UNRECOVERABLE`，均不是平台扣费凭证，也不接收自由文本。参数不合法400；活动任务、等待不足、金额不符、重复关闭已结算请求或同UUID改意图409；迁移/事务/存储未就绪503，不回显底层诊断。

成功时**全部原预留额从held转入accounted，可用额度不增加**，原请求变为SETTLED/`MANUAL_RESERVED`，token用量保持未知而非伪造0；释放该仓库等待状态但保留旧成功解读，不获得新的模型结果，也不修改已发布日报。新分析仍需另一次受控采集意图并通过原预算。审计记录操作者、原请求/月、金额、固定理由和数据库UTC时间，处理编号及原请求各自唯一。相同完整意图重放返回原时间及`replayed=true`；另一编号关闭同笔、同编号换参数均拒绝。已存在处理事实可在后续任务活动时查询/重放。

月份暂停状态不因关闭解除；晚到可信响应不能重算金额或覆盖解读，晚到超界用量仍暂停原月。正常结算和人工关闭竞争时，只能有一方首次结算；审计失败则连同费用/仓库槽及相关过期任务回收一起回滚。费用处理不是查询平台实际账单，也不能限制窃取者脱离本应用使用Key；十元仍是本地保守控制边界。

## 每日定时与安全补偿

默认`POLYHOUSE_TREND_SCHEDULE_ENABLED=false`。启用须同时满足模块/任务/定时开关、有效参数和已迁移014；AI启用时配置格式须就绪，AI关闭可发布基础日报。首次默认北京06:30，`POLYHOUSE_TREND_DAILY_TIME`可设HH:mm；时区固定Asia/Shanghai。`polyhouse.trend.schedule.retry-delay-minutes`默认45、允许45～120，首次和补偿各有十分钟窗口，两窗口必须在同一北京日期内；无效配置不启动定时受理。

后台每30秒检查数据库UTC换算的北京时间。06:30～06:39:59最多受理一次；默认07:15～07:24:59最多补偿一次，日期/尝试唯一且和任务占槽同事务。忙时不排第二批，可在窗口内稍后再检查；已有当期公开日报或本次已受理事实则跳过。手动和自动共用原单槽、代数、冷却、容量、运行上限、费用及原子发布。

补偿必须同时满足：首次明确FAILED且仍处COLLECTING、无公开日报、没有活动任务、全局无RESERVED/UNKNOWN费用。进入ANALYZING/PUBLISHING、已发布、过期中断都不补偿；不会自动重发结果不明的收费请求。错过首次窗口且没有首次事实，不会借补偿窗口做“首次补采”，更不会伪造旧期。采集期号仍由实际采集开始时间决定，自动不覆盖当日已有内容，屋主明确手动更新仍可生成新版。

系统任务不存JWT或用户ID，每次调用/发布前复核配置及受理映射。未启用定时的节点不领取系统队列，避免误中断其他合格节点的任务；所有节点停用后，未领取任务仍由原30分钟期限回收，运行中执行权也需通过配置校验。手动仍按最新屋主/凭据版本校验。打开配置及重启可能受理新任务或继续已受理任务，真实小样本联调先保持定时关闭。仅验证运行中线程及可控数据库时间，未等待真实06:30或部署云端。G3d已补本地[Render外部预热/限时保活](trend-render-wake.md)，只探测匿名应用就绪，不调用生成接口；默认关闭、尚未远端启用，不能替代实际云端保障验收。

## 可选公开内容缓存

默认`POLYHOUSE_TREND_CACHE_ENABLED=false`，完全走原数据库路径。开启后仅一个`polyhouse:trend:view:latest` hash，恰好id/stamp/payload三字段、900秒TTL、最多256KiB字符/1MiB UTF8字节；不存README、Key、用户、任务或费用。主要加速最新日报；历史也校验已发布身份但通常回源，日期列表不缓存，不按访客/日期增殖键。HTTP继续no-store，服务端Redis缓存不代表浏览器缓存开放。

每次读先取数据库权威发布头，匹配ID和UTC微秒时间后才接受缓存，并校验JSON与版本；错配/损坏回源不可变版本，验证后回填。数据库不可用返回503，不用旧缓存掩盖发布状态。读取不续TTL；回填用生产Lua原子检查20位时间字符串，拒绝旧请求覆盖新缓存或相同时间不同ID，不用double比较微秒。读取旧字段/负载前先检查长度，写入还检查参数与体积。

Redis故障后暂停缓存尝试30秒，按原连接/读取超时受控回源，不更改认证Redis配置；首次故障仍可能等到已有Redis超时。公开读并发仍为2，数据库查询三秒上限。发布事务不读写Redis，发布后首次阅读按新指针回填，缓存写失败不会回滚已发布报告；错误类型/超大值/坏JSON/乱序等已验。

## 验证入口

G4新增[真实HTTP隔离与官方小样本入口](trend-g4-acceptance.md)：`Verify -TrendHttpOnly`不付费，`Invoke-TrendAiLocal -Action VerifyLive -CurrencyCny`才在用户隐藏输入后执行真实样本，专属持久预算一角，不迁移开发库。后者与普通Run及下面的历史专项分开，不能混入默认全量账号夹具。

先运行`./polyhouse/scripts/Invoke-LocalAuth.ps1 -Action Inspect`，再运行`-Action Verify -TrendOnly`。分别创建清理G1两表、G2五表、G3a七表、费用八表、定时九表随机库；不创建用户、不连接真实Redis/AI、不改开发库。新专项含多实例/重启防重、唯一补偿、窗口/错过日期、系统身份/停用、014回退，并实际由定时线程受理→执行器→模拟AI账本→发布→模拟Redis故障回源，不等于真实认证/平台账单/强杀JVM验收。

真实缓存Lua专项单独运行`./polyhouse/scripts/Invoke-LocalAuth.ps1 -Action Verify -TrendRedisOnly`，默认复用既有WSL保活/就绪检查。只在本机Redis写随机`polyhouse:trend:test-cache:UUID`键并清理，不用正式内容键、认证键或FLUSH；不连接MySQL/AI。自行管理本机Redis时加`-SkipWslRedis`；临时随机端口可加`-TrendRedisPort 端口`，仅为本专项的JVM覆盖主机/端口，不能用于普通Run，也不改私有预设。

本轮Ubuntu WSL返回`HCS_E_CONNECTION_TIMEOUT`，原本机启动环境未验、没有强制重启WSL。改用已缓存Redis镜像创建只绑定127.0.0.1随机端口、有资源上限的临时容器，实测微秒乱序/并发、TTL及损坏/超大字段，已清理容器。Docker是本轮替代验证环境，不是项目新增运行依赖；G4需另行确认可用Redis及真实运行链路。

在 `polyhouse/` 可定向运行：

```powershell
mvn -q compile -DskipTests
mvn -q '-Dtest=GithubTrendingParserTest,GithubTrendClientTest,GithubTrendHttpTest,TrendControllerTest,TrendReportServiceTest,TrendReadLimitHttpTest' test
```

真实来源兼容检查显式运行 `mvn -q '-Dtest=GithubTrendLiveTest' '-Dpolyhouse.trend.live=true' test`，仅匿名读日榜及第一项元数据，不访问数据库或 AI。普通测试不开此开关，跳过不算真实可达性通过。

仅追加 Jsoup 1.24.1，现有依赖版本不变。已核对[官方发布](https://jsoup.org/news/)和[安全公告](https://github.com/jhy/jsoup/security/advisories/GHSA-pmhh-3w7g-xqp8)：这里只解析有大小边界的 HTML，不使用公告涉及的 Cleaner/自定义 Safelist，也不注入 HTML 展示。外部 JSON 另设嵌套、字符串及数值长度限制。

2026-11-16新增管理入口复查[Tomcat公告](https://tomcat.apache.org/security-10)及[Spring功能路由路径穿越公告](https://spring.io/security/cve-2024-38819/)；当前Tomcat10.1.61包含该页列出的10.1.61及此前修复，新入口为注解Controller，没有RouterFunctions文件资源映射。上述Jsoup Cleaner条件也未新增。未升级依赖，也不把定向触发条件复查表述为全依赖无漏洞证明；上线前仍按既有安全流程复核冻结版本。
