# 只读策划书检索索引

原件：[Polyhouse_Ultimate_Master_Blueprint.docx](../../Polyhouse_Ultimate_Master_Blueprint.docx)。本索引仅帮助定位；不修改原件、不授予新实施范围，也不替代 [AGENTS.md](../../AGENTS.md)。状态核对日期：2026-11-08。

按下表的原文章节标题定位，读取与任务相关部分；跨模块时补读关联章节。后续批准决定与当前实现有差异时先核对对应计划、日志和源码，不照抄原文示例。

| 原文章节 | 用途 | 当前状态与后续依据 |
| --- | --- | --- |
| 一、全局命名规范与跨生态工程目录标准；1.1 / 1.2 | 命名、后端分层、前端 core/workers 与工具注册表 | 命名、分层有效；目录树包含尚未实现的预留文件，不能据树认定业务存在 |
| 二、Phase 1；2.1 | 技术底座、认证及会话 | 底座已实现；版本看现有配置，不按旧文示例升级 |
| 二、Phase 1；2.2 | 身份枚举与算力闸门 | 已由四角色与精确管理授权补充；不可只用权重授予管理权，见下方账号计划 |
| 二、Phase 1；2.3 | 导航、登录窗、个人小窗 | 已实现并迭代；公共注册已关闭，视觉以方案二为准 |
| 三、Phase 2；3.1 | 妙替纯端侧处理与云模板设想 | 本地小型文本批处理已实现；云模板未接入 |
| 三、Phase 2；3.2 / 3.3 | 文件输入、编码、替换算法与导出示例 | 当前为显式选编码、最左同起点最长匹配、下载副本；不自动回退编码、不原地覆盖，旧算法示例不能直接替换现内核 |
| 四、Phase 3 | 妙压体积卡位、证件照/PDF 与云预设 | T1～T4 本地单图/批量已实现并合入主线；T5 换底/PDF/云预设已否决，见[妙压批准计划](../plans/target-kb-compression.md) |
| 五、核心架构扩展预留规范 | toolRegistry、纯算法、扩展 JSON、AI/部署预留 | 注册表与隔离有效；AI、云预设仍属预留；部署采用托管架构，见 dev.13 |
| 六、数据库完整建表脚本 | 原始四表设计 | 仅供理解基线；已有数据库用追加迁移，不重放初始化，见运维说明 |
| 七、研发落地计划单；7.1 | 原始阶段及验收设想 | 历史规划；五次错误锁定已明确取消，当前允许立即重试；时间/性能口号不是实测承诺 |
| 七、7.2 | 最初 DEV_LOG 模板 | 已被根 AGENTS 契约与分阶段日志工作流覆盖，不再复制为工程规范 |

当前有效事实入口：

- [状态与阶段](../../DEV_LOG.md)、[方案二设计](frontend-v2.md)、[妙替既有边界](../dev-log/v1.0-dev.5.md)。
- [前端体验参考](frontend-experience.md)：仅宏观设计、跨页面改版或大规模前端查漏时读取；不纳入每轮必读，也不替代根契约。
- [账号、权限与资料审核批准计划](../plans/controlled-beta-account-management.md)：P0～P4 完成；[U1～U4 整理计划](../plans/pre-p5-account-workspace-ui.md)已收口。云端放行条件见[上线验收清单](../dev-log/launch-preparation.md)。
- [账号安全](../operations/account-security.md)、[屋主开户](../operations/account-provisioning.md)、[账号管理](../operations/account-management.md)、[资料审核](../operations/profile-moderation.md)：对应接口、迁移和隔离验收。
- [上线前质量收敛计划](../plans/prelaunch-quality-hardening.md)：阶段成果已合入 dev，验收与边界见 [dev.12](../dev-log/v1.0-dev.12.md)。
- [云端架构与头像数据库存储](../dev-log/v1.0-dev.13.md)：GitHub Pages 前端已上线，Render、TiDB Cloud 与 Upstash 待云端验收。后端依赖升级未纳入本阶段。

“未实现/暂缓”不等于永久废止。原文没有完整的留言、社交或快传方案，不虚构这些章节；它们不在当前范围。
