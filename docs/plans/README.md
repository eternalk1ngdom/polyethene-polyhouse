# 项目计划索引

这里保存待讨论、待批准、实施中与已归档的功能计划；工程约束仍只以根目录 [AGENTS.md](../../AGENTS.md) 为准，完成事实与实际验证记录在 [DEV_LOG.md](../../DEV_LOG.md) 指向的阶段日志中。

## 活跃与待授权计划

| 计划 | 状态 | 所属阶段 |
| --- | --- | --- |
| [星潮榜单异动、自动拉取显性化与 AI 费用双口径对账](startide-delta-automation-billing.md) | 已批准待实施（3 个独立闭环：榜单新面孔与筛选、自动采集显性化与简报顺路自动生成、AI 费用双口径对账） | [dev.24](../dev-log/v1.0-dev.24.md) |
| [测试体系升级与仓库维护修复验收计划](testing-upgrade-and-maintenance-verification.md) | 本地 `dev` 已收口（阶段 A～C 与 D1/D2）；`#16` 远端 Pages 双作业首轮发布验收与推送待确认 | [dev.21](../dev-log/v1.0-dev.21.md) |
| [仓库维护计划](repository-maintenance-backlog.md) | 高价值 10 项（#1～#6、#10、#15～#17）已合入本地 `dev`；#14 不实施，其余 6 项延后观察 | [dev.21](../dev-log/v1.0-dev.21.md) |
| [前端趣味氛围与个性化体验储备计划](frontend-ambience-backlog.md) | 远期低优先级待办（P3 创意储备，未批准实施、暂不排期） | 远期待办 |
| [本地自定义卡面](local-profile-cover.md) | 静态卡面及选图异常修复已合入 `dev`；短循环动态卡面列为 P3 待办，访客主页待后续 | [dev.20](../dev-log/v1.0-dev.20.md) |
| [星潮 2B：7 日风口简报](startide-window-briefing.md) | B1～B5 全闭环经 PR #16 合入 `dev`（`da62b75`）；云端 `015` 迁移、对账与部署待授权 | [dev.18](../dev-log/v1.0-dev.18.md) |
| [GitHub 开源技术雷达](github-tech-radar.md) | G4 真实小样本对账通过，G6 真实联调及本机开发库迁移完成，云端待验 | [dev.15](../dev-log/v1.0-dev.15.md) |
| [星潮后端解读质量与可用性](startide-insight-quality.md) | 采集、生成与发布本机工程收口，正文可读性转入[后续写作专项](startide-insight-quality.md#文章结构专项候选) | [dev.15](../dev-log/v1.0-dev.15.md#工程收口与文章交接) |
| [云端上线验收](../dev-log/launch-preparation.md) | GitHub Pages 已发布，Render 主线部署与账号列表已验收；后续发布待授权 | [dev.13](../dev-log/v1.0-dev.13.md) |

## 已收口历史计划（按需查阅）

| 计划 | 收口状态 | 所属阶段 |
| --- | --- | --- |
| [上下文与 Token 降本瘦身专项计划](context-token-optimization.md) | 已完成并快进合入 `dev`、同步 `origin/dev`（双文档治理、冷热归档、体检与宪法哨兵、静默脚本加固） | [dev.23](../dev-log/v1.0-dev.23.md) |
| [架构解耦止血与 AI 协作降噪计划](architecture-decoupling-optimization.md) | 已完成并快进合入 `dev`、同步 `origin/dev`（全部 2 批次 4 项） | [dev.22](../dev-log/v1.0-dev.22.md) |
| [项目架构解耦与可维护性专项评估计划](architecture-decoupling-assessment.md) | 已完成只读诊断，报告归档至[架构解耦评估报告](../design/architecture-decoupling-assessment.md) | [dev.22](../dev-log/v1.0-dev.22.md) |
| [个人空间卡面与硬币解锁](profile-card-backgrounds.md) | 默认 A、G 与 S3 双端素材验收完成，购买/解锁/佩戴已联调合入 `dev` | [dev.20](../dev-log/v1.0-dev.20.md) |
| [前端视觉与体验精修候选清单](frontend-polish-phase.md) | 已完成（F1/F3 由 PR #12、F2 由 PR #13、F4/F5 由 PR #15 合入 `dev` `b88ab20`） | [dev.17](../dev-log/v1.0-dev.17.md) |
| [首页工具列表之后，留什么？](home-closing-section.md) | 预览 B 后采用 A，独立说明区已移除，旧书签落到页脚 | [dev.16](../dev-log/v1.0-dev.16.md#首页收尾区方案) |
| [星潮前端设计](trend-frontend-design.md) | G6 本地真实浏览器联调通过，保留首版接口与交互基线 | [dev.15](../dev-log/v1.0-dev.15.md) |
| [星潮阅读与发布台改版](startide-reader-redesign.md) | 第 1～3 步已验，第 4 步联合验证范围见阶段记录 | [dev.15](../dev-log/v1.0-dev.15.md) |
| [框架优化与文档重塑](framework-optimization.md) | 已通过 [PR #4](https://github.com/eternalk1ngdom/polyethene-polyhouse/pull/4) 合入 `dev` `be1a108`，F5 按需跳过；`main`/部署未更新 | [dev.14](../dev-log/v1.0-dev.14.md) |
| [Render 后端生产适配与容器化](render-docker-production.md) | 本地容器与主线部署已完成，云端专项状态见验收清单 | [dev.13](../dev-log/v1.0-dev.13.md) |
| [上线前质量收敛](prelaunch-quality-hardening.md) | 阶段成果已合入 `dev`，验收边界见阶段日志 | [dev.12](../dev-log/v1.0-dev.12.md) |
| [妙压：图片体积卡位（TargetKB）](target-kb-compression.md) | T1～T4 及取消保护修复已完成，2026-11-03 经确认合入 `main`；T5 已否决 | [dev.11](../dev-log/v1.0-dev.11.md) |
| [妙替功能深化：本地词库、分享与命中预览](poly-replace-deepening.md) | R1～R7 已完成并复审（R4 不合并，R7 500 候选无默认排除） | [dev.10](../dev-log/v1.0-dev.10.md) |
| [文档、上下文与验证工作流整理](context-efficiency.md) | 2026-11-01 已批准交付 | [dev.9](../dev-log/v1.0-dev.9.md) |
| [P5 前：个人中心与管理工作台整理](pre-p5-account-workspace-ui.md) | U0～U4 已收口，普通构建与隔离页面验收通过（不代表 P5/部署完成） | [dev.8](../dev-log/v1.0-dev.8.md) |
| [受控内测：账号、权限与资料审核](controlled-beta-account-management.md) | S1～S9 已批准，P0～P4 完成；P5 上线部分已搁置（2026-10-31），历史待人工核对 | [dev.7](../dev-log/v1.0-dev.7.md) / [dev.8](../dev-log/v1.0-dev.8.md) |

## 历史批准决议备忘

- 2026-10-31 决定：上线/部署相关计划整体搁置，后端依赖升级方案已被用户否定。
- 2026-11-01～11-02：U4、文档治理与主线整合完成，妙替 R1～R7 按选择独立验收交付（R4 导出/导入不合并，R7 500 候选且无默认排除），不恢复上线。
- 2026-11-03：用户批准妙压独立修复、隔离试合与验证，原工作树保留，随后确认集成分支推送及正式 `main` 合入/同步；部署及目录清理仍不包含在内。
- 妙替计划已区分 R1 批准范围与后续候选；受控内测与 U1～U4 详案仅在相关维护/追溯时读取。计划中的候选设计不表示已实现，用户变更需求时在同一计划中更新决定。
