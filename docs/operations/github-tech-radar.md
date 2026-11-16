# 技术雷达后端归档、查询与屋主任务

当前已实现[阶段方案](../plans/github-tech-radar.md)的G1、G2核心及G3a屋主持久任务。费用查询/待核对操作、每日定时、Redis内容缓存和前端未交付；真实模型样本仍待私有配置验证。公开查询不会自动生成数据。

## 启用与数据来源

- 默认 `POLYHOUSE_TREND_ENABLED=false`，不需 GitHub 或 AI 密钥，既有启动方式保持。关闭时新查询返回统一 503，其他功能照常启动。
- 明确目标库并完成下述迁移后，通过运行环境设置 `POLYHOUSE_TREND_ENABLED=true`。可用私有变量 `POLYHOUSE_GITHUB_TOKEN` 提供公共仓库只读凭据，不写入提交文件或发到聊天。
- 屋主任务另需012迁移和`POLYHOUSE_TREND_JOBS_ENABLED=true`。只读报告不需要启用任务；启用任务但保持AI关闭可以发布基础日报。Key配置见[安全输入说明](deepseek-key-safety.md)，不要发到聊天；`Invoke-TrendAiLocal.ps1 -EnableAi`仍仅打开模块/AI，**不会打开任务开关**。
- 任务开关启用后，重启可继续已经受理且未过期的QUEUED任务，届时可能产生账本允许的付费；先核对已有任务及费用状态。关闭开关不会自动迁移、退款或开启每日调度。

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

任务回退先停用并停止执行器，等待外部请求结束，核对待执行任务及费用预留。可以暂留两表供排查；确需删除并已确认数据恢复方案后，先`tool_trend_job_slot`后`tool_trend_job`，保留010/011。任务表引用报告版本，完整回退必须先处理012，再处理010；费用事实不因任务回退删除。开发/TiDB迁移和真实库回退未执行。

同日更新追加版本，行锁和短事务切换指针；较早采集不得覆盖较新版本。重试同一 UUID 不复制报告，也不把旧版本重新设为当前。

## 屋主受控任务

| 路径 | DTO | 返回与约束 |
| --- | --- | --- |
| `POST /api/manage/trend/jobs` | JSON `{"requestId":"客户端生成的UUID"}` | 202、`accepted/replayed/job`，不等待整批采集；只更新当前日榜，不接受来源URL、期号或角色 |
| `GET /api/manage/trend/jobs` | 查询参数`jobId=UUID` | 200、任务状态/阶段/时间/已发布reportId/固定原因码；无编号404 |

两项均需现有屋主Bearer会话，初始密码需先改密；其他三角色403。服务事务重新验证JWT、Redis会话及数据库最新屋主记录，不用权重替代精确管理授权。统一Result、no-store；非法UUID400，配置/存储未就绪503。管理入口30次/分钟、突发6、并发2，384KiB请求体；超限429/Retry-After，伪造转发头或共享地址不另开额度。生产还受既有连接和五秒读取等预算。

一次更新意图始终使用相同UUID；重复返回原事实，活动期间另一个UUID返回当前任务且`accepted=false`，没有再排一批。终结后新意图受60秒冷却。受理时冻结是否允许AI；打开AI但配置不完整时不受理新的AI任务。任务/Key开关分别控制，不自动打开。

状态QUEUED→RUNNING→SUCCEEDED/PARTIAL/FAILED/INTERRUPTED；阶段为QUEUED/COLLECTING/ANALYZING/PUBLISHING/DONE。PARTIAL表示已发布但解读不完整，基础日报关闭AI时也属此状态；通过reportId区分已经发布。失败保留最近阶段及固定原因，不返回内部异常。只读内部任务可在执行开关关闭时排查，但仍必须通过屋主认证且数据库已迁移。

从受理起最多30分钟，数据库UTC时钟；执行器每30秒维护，租约2分钟。模拟重建服务验证QUEUED可以领取；RUNNING过期标中断不重放。旧执行的心跳、发布和释放均校验槽与代数。每次外部调用前再查角色/凭据版本，改密、重置或禁用会停止后续调用；退出登录不撤销已经接受的后台意图。已经发送的请求不能保证远端取消，未知计费继续持有，不自动退款或重试。

最多1000任务，新受理时清理超过90天的终结任务；有任何费用RESERVED/UNKNOWN时保守保留所有任务上下文，达到容量拒绝受理。发布版本和费用不随清理删除。当前没有费用关闭接口，**不要手工清空账本或预留来恢复调用**；G3b补受控待核对流程。当前也没有每日定时器；维护线程不会主动创建采集任务。

## 验证入口

在仓库根目录先运行 `./polyhouse/scripts/Invoke-LocalAuth.ps1 -Action Inspect`，再运行 `./polyhouse/scripts/Invoke-LocalAuth.ps1 -Action Verify -TrendOnly`。分别创建并清理G1随机两表库、G2随机五表库、G3a随机七表库，不创建用户、不连接Redis、不调用付费AI、不改开发库；其他专项保留。G3a含重建服务模拟重启、失效租约/角色、并发受理、发布事务回滚、容量清理和模拟AI的后台整链路，不等同真实服务认证或强杀JVM验收。

在 `polyhouse/` 可定向运行：

```powershell
mvn -q compile -DskipTests
mvn -q '-Dtest=GithubTrendingParserTest,GithubTrendClientTest,GithubTrendHttpTest,TrendControllerTest,TrendReportServiceTest,TrendReadLimitHttpTest' test
```

真实来源兼容检查显式运行 `mvn -q '-Dtest=GithubTrendLiveTest' '-Dpolyhouse.trend.live=true' test`，仅匿名读日榜及第一项元数据，不访问数据库或 AI。普通测试不开此开关，跳过不算真实可达性通过。

仅追加 Jsoup 1.24.1，现有依赖版本不变。已核对[官方发布](https://jsoup.org/news/)和[安全公告](https://github.com/jhy/jsoup/security/advisories/GHSA-pmhh-3w7g-xqp8)：这里只解析有大小边界的 HTML，不使用公告涉及的 Cleaner/自定义 Safelist，也不注入 HTML 展示。外部 JSON 另设嵌套、字符串及数值长度限制。

2026-11-16新增管理入口复查[Tomcat公告](https://tomcat.apache.org/security-10)及[Spring功能路由路径穿越公告](https://spring.io/security/cve-2024-38819/)；当前Tomcat10.1.61包含该页列出的10.1.61及此前修复，新入口为注解Controller，没有RouterFunctions文件资源映射。上述Jsoup Cleaner条件也未新增。未升级依赖，也不把定向触发条件复查表述为全依赖无漏洞证明；上线前仍按既有安全流程复核冻结版本。
