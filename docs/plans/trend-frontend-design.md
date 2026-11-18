# 技术雷达前端设计草案（G5）

2026-11-18。主方案、名称和首批界面范围待评审。业务边界以[技术雷达阶段方案](github-tech-radar.md)为准，接口细节及运维限制见[模块运行说明](../operations/github-tech-radar.md)。

## 产品入口与阅读体验

- 首页展示顺序为主功能 → 妙替 → 妙压。主功能使用首位主推入口，突出「每日 GitHub 热门项目与中文解读」。G6 核对导航、首页搜索和分类对同一入口的行为。工具注册表沿用稳定标识和路由，中文名称确认后替换展示文案。
- 暂用「星潮 · 开源项目日报」。备选为「星见」「拾源」「开源雷达」「开源小报」。命名是展示层决定，路由候选为 `/trend`，不随品牌文字变化。
- 独立内容页采用期刊式排版。短报头用墨蓝紫做标题区，正文用暖白阅读底色、墨色文字、细分隔线和少量深粉突出交互。沿用聚妙屋导航、身份和可访问交互，内容区的版式与密度按阅读任务决定。宽屏保持正文可扫读，小屏用单列，长仓库名允许换行。
- 默认读最新发布期。报头下面放期次、采集时间、榜单来源及本期语言筛选。历史从已有已发布日期中选，日期可通过 URL 分享。筛选只作用于当前一期，保持来源顺序及原始排名。
- 榜单一行放名称、语言、本期新增星标、原始简介和一句解读，更多数据和 AI 解读按需展开。清楚区分 GitHub 原始描述、项目材料明确的限制和 AI 推断。保留 GitHub 日榜和项目来源入口。缺失值显示未知，零作为真实数值显示。
- 屋主控制面板放在独立管理入口或现有屋主工作区的模块页。更新动作针对当前期，展示真实阶段和终态。读取者的历史期次和滚动位置不因后台更新或重读最新期而改变。

## 前端现在能拿到什么

所有接口使用统一 `Result<T>`，字段为 `code`、`message`、`data`，成功业务码为 0。就绪接口给运维探针消费，前端以实际业务接口响应判断内容能否读取。

| 接口 | 访问 | 输入与返回 |
| --- | --- | --- |
| `GET /api/trend/periods` | 公开 | `periodType` 默认 `DAILY`，`before` 为排他日期游标，`limit` 默认 14、最多 30。返回 `periodType`、倒序日期 `items`、`nextBefore`。空库返回空列表。 |
| `GET /api/trend/report` | 公开 | 省略 `periodKey` 读最新发布期，也可传北京日期 `YYYY-MM-DD`。返回该日期当前已发布版本。无内容 404，日期非法 400，模块关闭或读存储异常 503。 |
| `GET /api/trend/readiness` | 公开 | 可用时返回 `status=READY`、`scope=APPLICATION`，表示应用已启动。 |
| `POST /api/manage/trend/jobs` | 屋主 | 传客户端生成的 `requestId` UUID，返回 HTTP 202 和 `accepted`、`replayed`、`job`。活动任务存在时返回已有任务，同一次更新意图用原 UUID 查证或重放。 |
| `GET /api/manage/trend/jobs?jobId=UUID` | 屋主 | 按编号查询状态、阶段、来源、各阶段时间及已发布版本，找不到返回 404。 |
| `GET /api/manage/trend/fees` | 屋主 | 支持北京月 `month`、`limit`、排他游标 `after`。返回预算、已计入、占用、可用、人工计入、暂停状态及待核对页。 |
| `POST /api/manage/trend/fees/resolutions` | 屋主 | 提交处理 UUID、原 AI 请求 UUID、原预留微元、`COUNT_RESERVED` 和固定理由，将原预留全额转为已计入。须满足后端条件，重复操作可按原意图核对。 |
| `GET /api/manage/trend/fees/resolution?resolutionId=UUID` | 屋主 | 查询已提交的费用处理结果。 |

### 日报及项目字段

| 对象 | 完整字段及含义 |
| --- | --- |
| 日报 | `reportId` 版本 UUID、`periodType` 周期类型、`periodKey` 北京期号、`capturedAt` 采集时间、`publishedAt` 发布时间、`sourceUrl` 日榜来源、`insightComplete` 解读完整性、`items` 项目列表。最多 25 条，失败的更新保留上一已发布版。 |
| 项目 | `rankNum` 原排名、`repositoryId` 仓库编号（字符串或空）、`fullName` 全名、`repoUrl` GitHub 链接、`description` 原简介、`primaryLanguage` 主语言、`totalStars` 总星标、`starsAdded` 本期新增星标、`forksCount` Fork 数、`license` SPDX 许可证、`archived` 归档状态、`repoType` 仓库性质、`classificationReason` 分类依据、`metadataStatus` 资料状态、`readmeStatus` README 状态、`insightStatus` 解读状态、`insight` 可空的结构化解读。 |
| AI 解读 | `category` 主题、`definition` 一句话定义、`painPoint` 解决的问题、`comparison` 同类差异（可空）、`highlights` 亮点、`documentedLimitations` 文档明确限制、`inferredRisks` AI 推断风险、`analyzedAt` 生成时间、`readmeVersion` 参考资料版本、`model` 模型、`ruleVersion` 解读规则版本。 |

`repoType` 区分代码项目、文档资源和未确定。AI 的 `category` 是另一维度，未获解读的项目也可以有仓库性质。`ANALYZED`/`REUSED` 是有效解读，`STALE` 表示沿用旧解读并应标原生成时间，`NOT_REQUIRED` 表示无需分析。其他缺失状态继续展示基础资料。历史报告可能没有 `insight`。正文、提示词、原始模型响应与密钥不出现在公开数据中。

### 真实返回节选

G4 小样本的 2026-11-18 日报于北京时间 10:25 采集并发布，两条解读均已生成。以下节选其中一条的公开字段，星标是当次采集快照。仓库性质 `UNKNOWN` 表示基础分类证据不足。同一条数据已有 `ANALYZED` 解读，两个字段可以并存。

```json
{
  "code": 0,
  "message": "操作成功",
  "data": {
    "periodType": "DAILY",
    "periodKey": "2026-11-18",
    "capturedAt": "2026-11-18T02:25:29.347710600Z",
    "publishedAt": "2026-11-18T02:25:50.113321100Z",
    "sourceUrl": "https://github.com/trending?since=daily",
    "insightComplete": true,
    "items": [
      {
        "rankNum": 2,
        "fullName": "mvanhorn/last30days-skill",
        "primaryLanguage": "Python",
        "totalStars": 69130,
        "starsAdded": 696,
        "repoType": "UNKNOWN",
        "insightStatus": "ANALYZED",
        "insight": {
          "category": "AI_AGENT",
          "definition": "AI代理技能，并行检索Reddit、X、YouTube、HN等多平台内容并按互动数据评分后合成摘要。",
          "model": "deepseek-flash"
        }
      }
    ]
  }
}
```

### 屋主字段与后台能力

| 对象 | 完整字段及含义 |
| --- | --- |
| 更新任务 | `jobId`、`state`、`phase`、`aiRequested`、`createdAt`、`startedAt`、`completedAt`、`deadline`、`reportId`、`reason`、`trigger`。状态区分排队、运行、成功、已发布但解读不完整、失败和中断。阶段区分排队、采集、分析、发布和完成。任务来源区分手动和定时。时间及报告编号在相应阶段可空。 |
| 月度预算 | `month`、`budgetMicros`、`accountedMicros`、`heldMicros`、`remainingMicros`、`conservativeMicros`、`suspended`、`pending`、`nextAfter`。金额是人民币微元，1 元等于 1,000,000 微元，账本估算与平台实际扣款分别理解。 |
| 待核对请求 | `requestId`、`repositoryId`、`state`、`reservedMicros`、`createdAt`。状态为预留或结果未知。 |
| 费用处理回执 | `resolutionId`、`requestId`、`month`、`accountedMicros`、`reason`、`completedAt`、`replayed`。 |

后台支持 06:30 北京时间定时受理、满足条件时 07:15 补偿、可选 Redis 内容缓存、独立外部唤醒与预算防重。模块和定时默认关闭。管理接口要走现有屋主认证，退出或临时网络失败时按照响应处理。任务和费用共用每分钟 30 次管理限额，起步建议仅对已知且未终结的任务低频轮询，遵守 `Retry-After`。

### 影响设计的后端边界

- 现有公开接口只给每期当前已发布版本，没有按 `reportId` 浏览同日旧版、跨期搜索、周榜、月榜、项目趋势曲线、收藏或推送。首版语言筛选可用本期数据完成。
- 屋主可按已知 `jobId` 查任务，没有列出任务、发现当前活动任务、逐项目进度、百分比或取消接口。首版可以从提交响应跟踪本次任务，在同一浏览器恢复已知编号。若要打开页面直接看到自动任务、跨设备续看或浏览任务历史，另议补后端能力。
- 更新失败时阅读页继续呈现旧已发布内容。基础日报或部分解读可发布，展示状态应区别于任务失败。读取、重试读接口或浏览历史不会触发 AI。

## G6 候选实施顺序

| 切片 | 范围 | 完成证据 |
| --- | --- | --- |
| 1. 公开阅读 | 模块 API 类型与独立路由，最新期、历史选择、来源、折叠解读、一期内语言筛选。 | 真实脱敏样本与全空/部分/陈旧等代表性状态可读，日期 URL 可恢复，迟到响应不会覆盖新选择。 |
| 2. 首位入口 | 首页和工具导航的主推位置，保留妙替和妙压原顺序。 | 首页原搜索、筛选、两工具入口和手机窄屏抽查通过。 |
| 3. 屋主管理 | 手动更新及已知任务追踪，预算概览；待核对费用处理界面按首批评审范围决定。 | 验证屋主/非屋主、幂等、部分成功、失败、刷新、到期与限流。显示真实阶段。 |
| 4. 联调与收口 | 前端构建、受影响测试、桌面/手机/断点、鼠标和 Tab。 | 实际验证和遗留范围记入阶段日志，按项目流程提交；远端与部署按原流程。 |
