# 技术雷达后端基础归档与查询

当前交付为[阶段方案](../plans/github-tech-radar.md)的 G1 及公开查询部分。AI、屋主任务写接口、定时、Redis 内容缓存和前端尚未交付；查询不会自动生成数据。

## 启用与数据来源

- 默认 `POLYHOUSE_TREND_ENABLED=false`，不需 GitHub 或 AI 密钥，既有启动方式保持。关闭时新查询返回统一 503，其他功能照常启动。
- 明确目标库并完成下述迁移后，通过运行环境设置 `POLYHOUSE_TREND_ENABLED=true`。可用私有变量 `POLYHOUSE_GITHUB_TOKEN` 提供公共仓库只读凭据，不写入提交文件或发到聊天。
- 当前采集及发布只有内部 Java 服务，没有公网生成入口。采集返回有界材料，内部发布服务保存基础日报；G2/G3 后续接入解读、受控任务及定时。不要将运维命令暴露成公开 HTTP。

## 查询契约

| GET 路径 | 参数 | 行为 |
| --- | --- | --- |
| `/api/trend/periods` | `periodType=DAILY` 可省略；`limit` 默认 14、范围 1～30；可选 `before=YYYY-MM-DD` | 倒序 `items` 日期及排他 `nextBefore`，空库为空列表 |
| `/api/trend/report` | `periodType=DAILY` 可省略；可选 `periodKey=YYYY-MM-DD` | 未指定日期读最新已发布日报；指定日期读当日当前版本 |

均为匿名只读，统一 `Result<T>`，不恢复账号会话。无报告 404，非法日期/周期/限额 400，关闭或存储故障 503；超限 429 并带 `Retry-After`。沿用 no-store，不为公开内容改公共鉴权。

版本包含 UUID、北京期号、UTC ISO 采集/发布时间、来源和按来源排名的项目。仓库 ID 为字符串、未知数据为 null，README 正文、上游错误及凭据不公开。当前未分析，项目状态 `NOT_ANALYZED` 或资源项目 `NOT_REQUIRED`，`insightComplete=false`。未来前端须转义展示文本。

## 迁移与回退

1. 保持模块关闭，备份目标库，执行 `SELECT DATABASE()` 确认目标，盘点模块两表是否已经存在；不重放账号初始化。原 13 张基线业务表及 OWNER 保留。
2. 明确两表都不存在后，在选中的库执行 [010 迁移](../../polyhouse/sql/010_trend_reports.sql)。脚本无 USE，不自动运行；全新环境先按[基线初始化](database-bootstrap.md)，再追加本迁移。
3. 核对 JSON 对象 CHECK、索引、日期/版本组合外键。TiDB 须复核实际约束启用及兼容性；本轮仅在本机 MySQL 隔离库实测。
4. DDL 非整份脚本事务。失败先用 `SHOW CREATE TABLE` 盘点部分建表，不以 IF NOT EXISTS 掩盖不一致，不盲目重跑。
5. 回退先关闭模块并保留两表备份；旧应用可继续运行而暂留模块表。确需移除时，确认目标和数据可恢复后先删除 `tool_trend_daily`，再删除 `tool_trend_report_version`，不触及账号表。真实库删除或云端迁移须具体授权，本轮未执行。

同日更新追加版本，行锁和短事务切换指针；较早采集不得覆盖较新版本。重试同一 UUID 不复制报告，也不把旧版本重新设为当前。

## 验证入口

在仓库根目录先运行 `./polyhouse/scripts/Invoke-LocalAuth.ps1 -Action Inspect`，再运行 `./polyhouse/scripts/Invoke-LocalAuth.ps1 -Action Verify -TrendOnly`。专项只创建并清理随机 `polyhouse_trend_` 库，不创建用户、不连接 Redis、不改开发库；其他专项原行为保留。

在 `polyhouse/` 可定向运行：

```powershell
mvn -q compile -DskipTests
mvn -q '-Dtest=GithubTrendingParserTest,GithubTrendClientTest,GithubTrendHttpTest,TrendControllerTest,TrendReportServiceTest,TrendReadLimitHttpTest' test
```

真实来源兼容检查显式运行 `mvn -q '-Dtest=GithubTrendLiveTest' '-Dpolyhouse.trend.live=true' test`，仅匿名读日榜及第一项元数据，不访问数据库或 AI。普通测试不开此开关，跳过不算真实可达性通过。

仅追加 Jsoup 1.24.1，现有依赖版本不变。已核对[官方发布](https://jsoup.org/news/)和[安全公告](https://github.com/jhy/jsoup/security/advisories/GHSA-pmhh-3w7g-xqp8)：这里只解析有大小边界的 HTML，不使用公告涉及的 Cleaner/自定义 Safelist，也不注入 HTML 展示。外部 JSON 另设嵌套、字符串及数值长度限制。
