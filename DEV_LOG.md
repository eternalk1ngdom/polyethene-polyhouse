# 聚妙屋：当前状态与日志索引

更新时间：2026-11-16（Asia/Shanghai）。**v1.0 开发中。**

- 当前：[dev.15](docs/dev-log/v1.0-dev.15.md) [技术雷达](docs/plans/github-tech-radar.md)G1/公开查询及G2解读/预算核心已实现，编译/本轮76项通过；默认关闭，开发库未迁移。真实DeepSeek样本待私有配置，下一步G3屋主任务；定时/前端尚未实现，前端另行设计。
- 分支：`codex/github-tech-radar` 源自 dev `c06ed41`，含 PR #5 合并 `c7d391f` 及交接。main/部署未变，线上调度、远端操作另行授权。
- 基线：试用后 dev `9606cd4` 合入 main `aaa4ddc`，Render 已部署；用户确认公开前端可登录、账号列表正常。
- Q0/Q1布局完成：统一1320px/900px断点，修复妙压长名称撑宽；原目录/工作树及其他修改保留。
- 云端：[上线验收清单](docs/dev-log/launch-preparation.md)。采用 Render、TiDB Cloud 与 Upstash。
- 入口：[AGENTS.md](AGENTS.md) / [README.md](README.md) / [设计索引](docs/design/blueprint-index.md) / [计划索引](docs/plans/README.md)。不批量加载历史。

## 已实现与边界

- 认证、Redis 多端、唯一 OWNER、成长、P0～P4 及 U1～U4 已完成。公共注册关闭，屋主可创建 USER、ADMIN、INSIDER，TiDB 开户约束已核对。ADMIN 仅管理 USER，昵称/头像先审后发，历史资料待人工核对。
- 前端视觉基线 1.0（原方案二）：白底墨色、小窗/空间、“开门小屋”SVG及首页特色；方案一/选型历史保留。[设计](docs/design/frontend-v2.md) / [回滚](docs/operations/frontend-variants.md)。
- 妙替 R1～R7、妙压 T1～T4 已完成：本机文本替换、词库/草稿/分享及图片体积卡位/DPI/ZIP。规则见[指南](docs/guides/features.md)，验收见 dev.10/11；T5换底/PDF/云预设已否决。
- **本机有正式屋主**：不清空初始化，不在开发库跑 OWNER 夹具。凭据复用忽略的 `polyhouse/.idea/workspace.xml`，先 Inspect，勿复制/输出；Redis 启动检查非运行期监控。
- **后端依赖版本继续冻结**，仅批准 Tomcat 升至 10.1.61，候选镜像本地已验。AI核心已实现但真实样本未验，云模板未接入，3D/社交不在范围；服务器与公网操作须具体授权。

## 既有交付（详细验收见 dev.13）

- 头像本机裁剪为 160×160 WebP，经 JSON 提交审核并存数据库；迁移、接口、回退见[资料审核](docs/operations/profile-moderation.md)。
- 昵称/头像共用待审预览，通过理由可空、驳回必填；验收见[dev.13](docs/dev-log/v1.0-dev.13.md)。
- 开户可复制账号/一次性密码，默认遮住密码；列表详情只读，遗失凭据走重置。备份/试还原/重建、TiDB 21 条 CHECK 及屋主记录见[数据库运维](docs/operations/database-bootstrap.md)。
- 妙压页眉“图片处理”；两工具下载及入口的浏览器历史验收见 dev.13。
- 网页登录按电脑/手机发送 `PC` / `H5`，隔离 HTTP 已验双端同时在线。
- Render 登录/刷新、约一分钟唤醒、Pages 及未验边界见[上线清单](docs/dev-log/launch-preparation.md)；具体唤醒请求未确认。

## 历史索引（按需）

| 阶段 | 已完成内容 |
| --- | --- |
| [dev.14](docs/dev-log/v1.0-dev.14.md) | PR #4/#5 合入 dev；[评估](docs/design/framework-assessment.md)保留分层、跳过 F5；历史前端346项/构建、后端编译/34项通过 |
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

旧清单见[入口快照](docs/dev-log/entry-before-context-governance.md)。阶段号、本地提交、历史测试不等于发布或已推送。
