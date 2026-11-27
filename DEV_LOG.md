# 聚妙屋：当前状态与日志索引

更新时间：2026-11-27（Asia/Shanghai）。**v1.0 开发中。** 当前阶段：[v1.0-dev.27.md](docs/dev-log/v1.0-dev.27.md)。

## 当前焦点

- **dev.27 已合 `dev` 并同步 `origin/dev`**：星潮阅读页纯前端晨雾弥散柔光页头（零位图、适配两视图与实时期号/水印）与彩色日历图标落地，前端 611/611 全绿、五视口 8080/5173 视觉巡检通过；下一步评估 DeepSeek 计费精准对账方案。

## 🔒 永久钉选区（未经用户确认禁止删除）

- **本机正式屋主**：禁清空初始化、禁在开发库跑未隔离 OWNER 夹具；凭据复用忽略的 `polyhouse/.idea/workspace.xml`（先 Inspect，勿输出）。
- **版本与范围红线**：后端依赖冻结（仅批 Tomcat `10.1.61`）；T5 换底/PDF/云预设已否决；云模板未接入，3D/社交不在范围；公网与线上部署须具体授权。
- **待授权与未闭环**：`main` 晋级、云端 `015` 迁移、平台账单对账、线上部署与 `#16` Pages 双作业首轮验收待确认；动态卡面、架构条件项及维护延后项 `#7/#8/#9/#11/#12/#13` 暂不排期；11-20 断源首故障仍缺现场证据。
- **线上基线与核心能力**：`dev` `9606cd4` → `main` `aaa4ddc`（Render/TiDB/Upstash/Pages，见[上线清单](docs/dev-log/launch-preparation.md)）；已交付认证/多端/唯一 OWNER/受控开户与资料先审后发（ADMIN 仅管 USER，历史资料待核）、视觉 1.0（[设计](docs/design/frontend-v2.md)/[回滚](docs/operations/frontend-variants.md)）、妙替 R1～R7、妙压 T1～T4（[指南](docs/guides/features.md)）、星潮雷达与 7 日简报、[前端技能](docs/operations/ui-ux-skill.md) v2.15.0。
- **常用入口**：[契约](AGENTS.md) / [README](README.md) / [设计](docs/design/blueprint-index.md) / [计划](docs/plans/README.md) / [验证](docs/operations/verification.md) / [审核](docs/operations/profile-moderation.md) / [数据库](docs/operations/database-bootstrap.md)。

## 近期阶段（滚动保留最近 4 期）

| 阶段 | 已完成摘要 |
| --- | --- |
| [dev.26](docs/dev-log/v1.0-dev.26.md) | 可复用归档日历组件、星潮接入、范式 A 手输融合与单月静默收口（已合 `dev` 并推 `origin/dev`） |
| [dev.25](docs/dev-log/v1.0-dev.25.md) | 星潮发布台并发 429 与待确认根治、日报动静分离与四卡统一、真机巡检、急停开关与 `017/018` 真库验收（已推 `origin/dev` `3fe6fb3`） |
| [dev.24](docs/dev-log/v1.0-dev.24.md) | 星潮榜单新面孔与退榜区、发布台自动采集状态与 7 日简报自动生成、AI 双口径对账与 `017` 迁移（已随 dev.25 合 `dev`） |
| [dev.23](docs/dev-log/v1.0-dev.23.md) | 上下文瘦身、冷热归档与永久钉选区、静默脚本/体检哨兵与 `AGENTS.md` 黄金基线锁定（已合 `dev`） |

更早阶段（dev.22 ～ dev.1）及早期环境备忘完整保留于 [历史阶段总归档](docs/dev-log/ARCHIVE.md)（按需查阅，不删历史）；阶段号/提交/历史测试不等于正式发布。
