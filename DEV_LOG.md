# 聚妙屋：当前状态与日志索引

更新时间：2026-11-26（Asia/Shanghai）。**v1.0 开发中。** 当前阶段：[v1.0-dev.23.md](docs/dev-log/v1.0-dev.23.md)。

## 当前焦点与未决边界

- **进行中（dev.23）**：[上下文与 Token 降本瘦身](docs/plans/context-token-optimization.md)（已完成双文档瘦身、测试静默脚本与 SSOT 降频同步）。
- **近期已合 `dev`**：架构解耦优化 4 项（[dev.22](docs/dev-log/v1.0-dev.22.md)，已推 `origin/dev`）；仓库维护 10 项与测试升级（[dev.21](docs/dev-log/v1.0-dev.21.md)）；个人空间卡面（[dev.20](docs/dev-log/v1.0-dev.20.md)）；星潮阅读/2B 简报（[dev.18](docs/dev-log/v1.0-dev.18.md)/[dev.19](docs/dev-log/v1.0-dev.19.md)）。
- **待授权/延后**：`main` 晋级、云端 `015` 迁移、账单对账、线上部署与 `#16` Pages 双作业首轮验收待确认；动态卡面、架构条件项及维护 `#7/#8/#9/#11/#12/#13` 延后；11-20 断源首故障缺现场证据。
- **线上基线**：`dev` `9606cd4` → `main` `aaa4ddc`，Render/TiDB/Upstash/Pages 已部署，见[上线清单](docs/dev-log/launch-preparation.md)。

## 长期红线与核心能力

- **本机正式屋主**：禁清空初始化、禁在开发库跑未隔离 OWNER 夹具；凭据复用忽略的 `polyhouse/.idea/workspace.xml`（先 Inspect，勿输出）。
- **版本与范围红线**：后端依赖冻结（仅批 Tomcat `10.1.61`）；T5 换底/PDF/云预设已否决；3D/社交不在范围；公网与部署须具体授权。
- **已交付核心**：认证/Redis 多端/唯一 OWNER/成长/开户与资料先审后发（ADMIN 仅管 USER，历史资料待核）；视觉 1.0（[设计](docs/design/frontend-v2.md)/[回滚](docs/operations/frontend-variants.md)）；妙替 R1～R7、妙压 T1～T4（[指南](docs/guides/features.md)）；星潮雷达与简报；[前端技能](docs/operations/ui-ux-skill.md) v2.15.0。
- **常用入口**：[契约](AGENTS.md) / [README](README.md) / [设计](docs/design/blueprint-index.md) / [计划](docs/plans/README.md) / [验证](docs/operations/verification.md) / [审核](docs/operations/profile-moderation.md) / [数据库](docs/operations/database-bootstrap.md)。

## 历史索引（按需查阅，`docs/dev-log/v1.0-dev.<N>.md`）

| 阶段 | 已完成摘要 |
| --- | --- |
| [dev.22](docs/dev-log/v1.0-dev.22.md) | 架构解耦诊断与轻量优化 4 项（已推 `origin/dev`） |
| [dev.21](docs/dev-log/v1.0-dev.21.md) | 仓库维护 10 项、测试升级 A～D、红线入 `AGENTS.md` |
| [dev.20](docs/dev-log/v1.0-dev.20.md) | 个人空间官方/本地卡面与选图异常修复 |
| [dev.19](docs/dev-log/v1.0-dev.19.md) / [dev.18](docs/dev-log/v1.0-dev.18.md) | 登录态收边、星潮降噪与大白话简报；星潮 2B 7 日简报 |
| [dev.17](docs/dev-log/v1.0-dev.17.md) / [dev.16](docs/dev-log/v1.0-dev.16.md) / [dev.15](docs/dev-log/v1.0-dev.15.md) | 前端精修 F1～F5 与月夜横幅；星潮演进与首页重绘；星潮基础 G1～G6 |
| [dev.14](docs/dev-log/v1.0-dev.14.md) / [dev.13](docs/dev-log/v1.0-dev.13.md) / [dev.12](docs/dev-log/v1.0-dev.12.md) | 框架评估与文档重塑；头像入库与云端验收；上线前质量收敛 |
| [dev.11](docs/dev-log/v1.0-dev.11.md) / [dev.10](docs/dev-log/v1.0-dev.10.md) / [dev.9](docs/dev-log/v1.0-dev.9.md) | 妙压 T1～T4；妙替 R1～R7；文档治理与规范去重 |
| dev.8 ～ dev.1 | P4 审核与 U1～U4（8）、账号管理（7）、视觉方案二（6）、妙替基础/成长/初始工程（5～1） |

旧清单见[入口快照](docs/dev-log/entry-before-context-governance.md)；阶段号/提交/历史测试不等于正式发布。
