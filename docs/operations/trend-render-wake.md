# 技术雷达：Render 外部预热与限时保活

对应[阶段方案](../plans/github-tech-radar.md#63-定时与部署边界)及[模块运维](github-tech-radar.md)。当前只有本地实现和隔离验证；未推送、启用 Actions、修改 Render 或验证真实冷启动。本说明不构成部署授权。

## 工作方式与覆盖范围

Render 免费 Web 服务空闲约15分钟可能休眠，入站 HTTP 请求可以唤醒，应用内定时线程不能唤醒停止的进程。使用外部 GitHub Actions 每五分钟探测，UTC 22:20～23:55 对应**次日北京时间06:20～07:55**，每天20次计划运行；每次最多三次 GET。脚本拒绝在北京06:20以前、08:00起发起探测，延迟到窗口外的运行会显示 `outside-window` 并跳过。

这覆盖现有06:30首次受理、07:15唯一补偿及其最晚07:24:59受理后30分钟任务期限。它不是全天保活，也不补造漏采。后台定时仍是唯一自动受理入口；改变后台首次时间、补偿延迟或任务期限后，必须一起重新核对 cron、脚本窗口和覆盖范围。**应用就绪不代表数据库、迁移、Redis、AI、预算或日报就绪**；任务结果用原有屋主任务查询接口确认。

外部脚本只调用 `GET /api/trend/readiness`，从不调用生成或管理接口，不携带屋主 JWT、DeepSeek Key 或 GitHub API Token。但部署后若后端原已开启自动采集和AI，唤醒应用会使原有定时任务有机会运行并产生受预算控制的费用；启用外部唤醒与启用付费采集仍须分别核对。

## 就绪接口及限制

- 接口只查询 Spring Boot 应用生命周期，未开始接收流量或正在关闭时返回503及 `Retry-After: 5`。正常启动后，即使模块关闭也可返回200；固定 `code=0`、`data.status=READY`、`data.scope=APPLICATION`。响应均 `Cache-Control: no-store`，不暴露内部配置。
- 无数据库、Redis、GitHub或AI调用，无身份恢复。复用公开读每实例60次/分钟、突发12、并发2预算，与日报阅读共享；生产仍先经过全站限额。不按来源建键，不信任伪造转发头。达到限额返回429及 `Retry-After`，脚本立即失败，交给下一次定时探测。
- 脚本仅接受 `https://单个服务名.onrender.com` 原点，禁止凭据、路径、查询、片段及非443端口；追加固定就绪路径，不跟随重定向。自定义域名需另行扩展白名单。
- 只需 Python 3.10+ 标准库与 curl 8.4+，不安装 Python 包或修改应用依赖。忽略 curlrc、显式关闭代理，不发送认证或 Cookie。连接最多5秒、单请求最多45秒、响应最多4096字节、失败间隔30秒、最多三次、整轮预算195秒；外层 Actions 作业最多五分钟。
- 503等临时状态、网络错误和冷启动 HTML 可有限重试；跳转、401/403/404/429、超大响应立即失败。只有固定 JSON 就绪响应算成功，不输出响应体、完整地址、异常原文或环境变量。未启用及窗口外跳过的退出码0不代表应用就绪。

## 后续获批后的启用顺序

1. 先完成G4及发布审批，部署包含该接口的后端；旧线上版本没有接口，404应失败，不能改成“任意200成功”。首次验证保持后台每日定时关闭，避免唤醒导致真实付费任务受理。
2. 将独立工作流 `.github/workflows/trend-wake.yml` 经批准合入仓库默认分支。它不修改已有 Pages 部署工作流；只读仓库、固定 checkout 提交、关闭凭据持久化，不申请部署权限。
3. 在 GitHub 仓库 **Settings → Secrets and variables → Actions → Variables** 新建 `TREND_WAKE_BASE_URL`，填实际 Render HTTPS 原点；新建 `TREND_WAKE_ENABLED`，值必须为小写 `true`。这些是非秘密变量；不要填任何 Key/令牌。未配置或其他值时作业默认不运行。
4. 在北京窗口内从 **Actions → Trend Render wake → Run workflow** 选择默认分支试运行，核对 `application-ready; dependencies-and-task-not-checked`，再验证真实冷启动和跨15分钟的连续保活。手动工作流也受默认分支和时间窗口限制，不支持全天唤醒。
5. 在G4配置/计费验收和部署授权范围内单独启用后台定时，核对首次受理、必要补偿和最终日报；唤醒成功不能替代这些验收。未调用AI的探测也会消耗 Actions 运行分钟和 Render 运行时间，按账户配额核对，不能归入10元AI月预算。

GitHub 定时可能延迟或丢失，只在默认分支触发，公开仓库长期无活动还会自动停用。这里是尽力保障；若必须准时，改用有持续运行保障的实例或可靠外部调度，另行评估和授权，不能以本脚本宣称准时送达。

## 本地验证与回退

默认安全检查（不设置启用变量时不会联网）：

```powershell
python polyhouse/scripts/Invoke-TrendWake.py
python -m unittest discover -s polyhouse/scripts/tests/trend -p test_trend_wake.py -v
```

测试启动随机 `127.0.0.1` HTTP 端口并实际调用 curl，覆盖就绪、503后恢复、HTML/错误JSON、跳转不跟随、响应体/超时、窗口/总预算和白名单；所有时间均为测试注入，不访问 Render 或付费服务。`--allow-local` 仅为本机测试开放数字回环 HTTP 原点，不允许其他私网地址；工作流不传该选项。代码和测试不需要真实服务地址或凭据。

停用时先把仓库变量 `TREND_WAKE_ENABLED` 改为 `false`，并取消已在运行的唤醒作业；变量不撤销已启动进程的环境。若需停止后端已受理或自动付费任务，必须按[模块运维](github-tech-radar.md)关闭相应开关并检查任务，而不是只停探测。就绪接口无表/配置迁移，可随版本回退；原费用审计和任务回退边界保持。

资料核对：[Render 休眠与唤醒](https://render.com/docs/free#spinning-down-on-idle)、[GitHub schedule 限制](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)、[curl 响应体限制](https://curl.se/docs/manpage.html#--max-filesize)。本轮仅本机验证，未执行上述云端步骤。
