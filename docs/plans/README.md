# 项目计划索引

这里保存待讨论、待批准和实施中的功能计划；工程约束仍只以根目录 [AGENTS.md](../../AGENTS.md) 为准，完成事实与实际验证记录在 [DEV_LOG.md](../../DEV_LOG.md) 指向的阶段日志中。

| 计划 | 状态 | 所属阶段 |
| --- | --- | --- |
| [仓库维护计划](repository-maintenance-backlog.md) | 高价值 10 项（#1～#6、#10、#15～#17）已在本地分支 `chore/repository-maintenance` 分三阶段完成并验收；#14 不实施，其余 6 项延后观察 | [dev.21](../dev-log/v1.0-dev.21.md) |
| [前端趣味氛围与个性化体验储备计划](frontend-ambience-backlog.md) | 远期低优先级待办（P3 创意储备，未批准实施、暂不排期） | 远期待办 |
| [本地自定义卡面](local-profile-cover.md) | 静态卡面及选图异常修复已合入 dev。短循环动态卡面列为 P3 待办；访客主页待后续 | [dev.20](../dev-log/v1.0-dev.20.md) |
| [个人空间卡面与硬币解锁](profile-card-backgrounds.md) | 默认 A「窗边初晴」已恢复，G「黄铜星盘」与暂定 S3「末班信号」已保存双端素材并通过九档宽度检查。购买、等级及身份开放、佩戴均已在隔离环境联调。 | [dev.20](../dev-log/v1.0-dev.20.md) |
| [星潮 2B：7 日风口简报](startide-window-briefing.md) | B1～B5 全闭环完成并经 PR #16 合入 `dev`（`da62b75`），云端 `015` 迁移与部署待授权 | [dev.18](../dev-log/v1.0-dev.18.md) |
| [前端视觉与体验精修候选清单](frontend-polish-phase.md) | 已完成（F1/F3 由 PR #12、F2 由 PR #13、F4/F5 由 PR #15 合入 `dev` `b88ab20`） | [dev.17](../dev-log/v1.0-dev.17.md) |
| [首页工具列表之后，留什么？](home-closing-section.md) | 预览 B 后最终采用 A，独立说明区已移除，旧书签落到页脚 | [dev.16](../dev-log/v1.0-dev.16.md#首页收尾区方案) |
| [GitHub 开源技术雷达](github-tech-radar.md) | G4 真实小样本对账通过，G6 真实联调及本机开发库迁移完成，云端待验 | [dev.15](../dev-log/v1.0-dev.15.md) |
| [星潮前端设计](trend-frontend-design.md) | G6 本地真实浏览器联调通过，保留首版接口与交互基线 | [dev.15](../dev-log/v1.0-dev.15.md) |
| [星潮阅读与发布台改版](startide-reader-redesign.md) | 第 1～3 步已验，第 4 步读者路径、屋主发布与后端联合验证的范围见阶段记录 | [dev.15](../dev-log/v1.0-dev.15.md) |
| [星潮后端解读质量与可用性](startide-insight-quality.md) | 采集、生成与发布的本机工程阶段收口。常规采集样本通过成稿展示，正文可读性转入[后续写作专项](startide-insight-quality.md#文章结构专项候选) | [dev.15](../dev-log/v1.0-dev.15.md#工程收口与文章交接) |
| [框架优化与文档重塑](framework-optimization.md) | 已通过 [PR #4](https://github.com/eternalk1ngdom/polyethene-polyhouse/pull/4) 合入 dev `be1a108`，F5 按需跳过；main/部署未更新 | [dev.14](../dev-log/v1.0-dev.14.md) |
| [文档、上下文与验证工作流整理](context-efficiency.md) | 用户已批准，2026-11-01 已交付；当前文档治理阶段 | [v1.0-dev.9](../dev-log/v1.0-dev.9.md) |
| [受控内测：账号、权限与资料审核](controlled-beta-account-management.md) | 已批准 S1～S9；P0～P4 功能完成；P5 上线部分已搁置（2026-10-31），历史待人工核对 | [v1.0-dev.7](../dev-log/v1.0-dev.7.md) / [v1.0-dev.8](../dev-log/v1.0-dev.8.md) |
| [P5 前：个人中心与管理工作台整理](pre-p5-account-workspace-ui.md) | U0～U4 已收口；普通构建/测试与真实隔离页面组合验收通过；不代表 P5/部署完成 | [v1.0-dev.8](../dev-log/v1.0-dev.8.md) |
| [妙替功能深化：本地词库、分享与命中预览](poly-replace-deepening.md) | R1～R7 已完成并独立复审，草稿历史保护已修复；R4 不合并，R5 确认来源，R6 全文统计与有界定位；R7 文件/目录统一勾选，500 候选、无默认排除、处理限额不变 | [v1.0-dev.10](../dev-log/v1.0-dev.10.md) |
| [妙压：图片体积卡位（TargetKB）](target-kb-compression.md) | T1～T4 已完成；独立取消保护修复已提交，隔离试合与验证完成，2026-11-03 经用户确认已合入/同步 main；T5 已否决 | [v1.0-dev.11](../dev-log/v1.0-dev.11.md) |
| [上线前质量收敛](prelaunch-quality-hardening.md) | 阶段成果已合入 dev；验收边界见阶段日志 | [dev.12](../dev-log/v1.0-dev.12.md) |
| [Render 后端生产适配与容器化](render-docker-production.md) | 本地容器和主线部署已完成，云端专项状态见验收清单 | [dev.13](../dev-log/v1.0-dev.13.md) |
| [云端上线验收](../dev-log/launch-preparation.md) | GitHub Pages 已发布，Render 主线部署和账号列表已验收 | [dev.13](../dev-log/v1.0-dev.13.md) |

2026-10-31 决定：上线/部署相关计划整体搁置，后端依赖升级方案已被用户否定。2026-11-01：U4、文档治理与主线整合完成，妙替 R1～R3 已独立验收。2026-11-02：R4 导出及仅替换全部导入分别批准、独立验收；用户明确不需要合并。后续 R5 按三项交互选择独立交付；R6 两个闭环已交付当前/整批逐条统计、有界高亮/删除标记与前后定位；R7 已按三项选择交付统一手选清单，500 候选且无默认排除；不恢复上线。

2026-11-03：用户批准妙压独立修复、隔离试合和验证，两个原工作树保留，随后用户明确确认集成分支推送及正式 main 合入/同步，已完成；部署及目录清理仍不包含在内。

妙替计划已将 R1 批准范围与后续候选区分，随 R1 纳入版本管理。受控内测与 U1～U4 详案仅在相关维护/追溯时读取，不是默认当前详案。

计划中的候选接口、数据字段、文件和验证场景不表示已经实现。批准记录应明确采纳的建议、选择与范围；用户修改需求时，在同一计划中更新决定，避免阶段日志和计划各自维护一套细则。
