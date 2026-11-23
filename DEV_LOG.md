# 聚妙屋：当前状态与日志索引

更新时间：2026-11-24（Asia/Shanghai）。**v1.0 开发中。**

- [个人空间卡面](docs/plans/profile-card-backgrounds.md)已完成默认候选母稿与六款横幅选型图，下一步选定默认主题并适配个人页；硬币解锁待立项。[记录](docs/dev-log/v1.0-dev.15.md#个人空间卡面选型)

- [前端精修 F4 与 F5](docs/dev-log/v1.0-dev.15.md#前端精修-f4-与-f5)已完成：分支 `feat/frontend-polish-f4-f5` 零后端完成后台视觉统一、星潮语义变量收敛与去米黄改纯白 `#ffffff`，构建、356 项单测、技能校验与 7 页×6 断点走查通过，待确认合入 `dev`；[F1/F3](docs/dev-log/v1.0-dev.15.md#前端精修-f1-与-f3)（PR #12）、[F2](docs/dev-log/v1.0-dev.15.md#前端精修-f2-工作台页头减重)（PR #13）与[星潮月夜横幅](docs/dev-log/v1.0-dev.15.md#星潮横幅-51-全景延展)（PR #14）已合入 `dev`（`6a30010`）。
- [首页收尾区](docs/plans/home-closing-section.md)最终采用方案 A，工具列表直接接页脚，专项分支已验。[验收记录](docs/dev-log/v1.0-dev.15.md#首页收尾区方案)
- 首页：[妙替纸蝶与妙压折页书](docs/dev-log/v1.0-dev.15.md#首页妙替与妙压卡面重绘)已完成，入口分别为“替换文本”和“压缩图片”，经 PR #9 合入 `dev`。[卡面集成记录](docs/dev-log/v1.0-dev.15.md#首页卡面与星潮集成)。
- 星潮：基础工程、文章结构、7 日风向统计分别经 PR #6、#7、#8 合入 `dev`；[详情页改版与 `radar-4` 升级](docs/dev-log/v1.0-dev.15.md#星潮详情页改版与-radar-4-升级)（1A 紧凑报头、3A 暖纸细线、正面 Few-Shot、`descriptionZh` 双语简介、默认关闭的 `low` 轻度思考及键名收敛）已完成离线/隔离验收与本机实跑（16/16 `radar-4`），正经 PR 集成至 `dev`。真实后端浏览器联调、平台账单对账及[2B 可选风口简报](docs/plans/startide-insight-quality.md#第二步跨日聚合看大风口单篇稳定后的下一闭环)留待后续推进；11 月 20 日下午断源的首个故障仍缺现场证据。
- [前端技能](docs/operations/ui-ux-skill.md)保留正式版 v2.15.0。WSL 服务已恢复，原 Redis 启动检查通过，Docker 作为备用入口。
- 基线：试用后 dev `9606cd4` 合入 main `aaa4ddc`，Render 已部署；用户确认公开前端可登录、账号列表正常。
- Q0/Q1布局完成：1320px/900px断点、妙压长名称修复；原目录/工作树保留。
- 云端：[上线验收清单](docs/dev-log/launch-preparation.md)。采用 Render、TiDB Cloud 与 Upstash。
- 入口：[契约](AGENTS.md) / [README](README.md) / [设计](docs/design/blueprint-index.md) / [计划](docs/plans/README.md)，历史按需读。

## 已实现与边界

- 认证、Redis多端、唯一OWNER、成长、P0～P4/U1～U4完成。公共注册关闭，屋主可创建USER/ADMIN/INSIDER，TiDB约束已核对；ADMIN仅管理USER，资料先审后发，历史资料待人工核对。
- 前端视觉基线 1.0（原方案二）：白底墨色、小窗/空间、“开门小屋”SVG及首页特色；方案一/选型历史保留。[设计](docs/design/frontend-v2.md) / [回滚](docs/operations/frontend-variants.md)。
- 妙替R1～R7、妙压T1～T4完成：文本替换、词库/草稿/分享、图片体积/DPI/ZIP；[指南](docs/guides/features.md)，验收dev.10/11。T5换底/PDF/云预设已否决。
- **本机有正式屋主**：不清空初始化，不在开发库跑 OWNER 夹具。凭据复用忽略的 `polyhouse/.idea/workspace.xml`，先 Inspect，勿复制/输出；Redis 启动检查非运行期监控。
- **后端依赖版本继续冻结**，仅批准 Tomcat 升至 10.1.61，候选镜像本地已验。云模板未接入，3D/社交不在范围。服务器与公网操作须具体授权。

## 既有交付（详细验收见 dev.13）

- 头像160×160 WebP入库、资料审核、工具入口/下载及PC/H5双端登录均已验，见[dev.13](docs/dev-log/v1.0-dev.13.md)和[审核运维](docs/operations/profile-moderation.md)。
- 开户支持复制账号/遮罩一次性密码，详情只读，遗失走重置。备份/还原/重建、TiDB 21条CHECK及屋主记录见[数据库运维](docs/operations/database-bootstrap.md)。
- Render登录/刷新、约一分钟唤醒、Pages及未验边界见[上线清单](docs/dev-log/launch-preparation.md)，具体唤醒请求未确认。

## 历史索引（按需）

| 阶段 | 已完成内容 |
| --- | --- |
| [dev.14](docs/dev-log/v1.0-dev.14.md) | PR #4/#5合dev；[评估](docs/design/framework-assessment.md)保留分层、跳过F5；历史验收通过 |
| [dev.13](docs/dev-log/v1.0-dev.13.md) | 头像入库、云端适配及 Render 与 GitHub Pages 发布验收 |
| [dev.12](docs/dev-log/v1.0-dev.12.md) | 上线前质量、回归与合并 |
| [dev.11](docs/dev-log/v1.0-dev.11.md) | 妙压 T1～T4、取消保护、隔离集成与 Git 同步 |
| [dev.10](docs/dev-log/v1.0-dev.10.md) | 妙替 R1～R7、复审与同步 |
| [dev.9](docs/dev-log/v1.0-dev.9.md) | 文档治理、主线整合与规范去重 |
| [dev.8](docs/dev-log/v1.0-dev.8.md) | P4 资料审核、U1～U4 整理与验收，已收口 |
| [dev.7](docs/dev-log/v1.0-dev.7.md) | P0～P3 账号安全/开户/管理 |
| [dev.6](docs/dev-log/v1.0-dev.6.md) | 方案二与品牌、备份回滚 |
| [dev.5](docs/dev-log/v1.0-dev.5.md) | 妙替、功能边界与有限容量/故障恢复 |
| [dev.4](docs/dev-log/v1.0-dev.4.md) / [dev.3](docs/dev-log/v1.0-dev.3.md) | 头像、首页、个人区域与成长 |
| [dev.2](docs/dev-log/v1.0-dev.2.md) / [dev.1](docs/dev-log/v1.0-dev.1.md) | 鉴权基础、成长与初始工程 |

旧清单见[入口快照](docs/dev-log/entry-before-context-governance.md)；阶段号/提交/历史测试不等于发布。
