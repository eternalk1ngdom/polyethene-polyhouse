# 聚妙屋：当前状态与日志索引

更新时间：2026-11-09（Asia/Shanghai）。**v1.0 开发中，未正式发布**。

- 当前：[v1.0-dev.13 云端架构与头像改造](docs/dev-log/v1.0-dev.13.md)，先读“当前摘要”。
- 基线：从 dev `0c353c3` 建立 `feature/avatar-compression-db`；GitHub Pages 已部署前端，云后端待验收。
- Q0/Q1首个布局闭环完成：统一1320px/900px断点，修复妙压长名称撑宽。原目录/工作树和其他修改保留。
- 云端：[上线验收清单](docs/dev-log/launch-preparation.md)。采用 Render、TiDB Cloud 与 Upstash。
- 入口：[AGENTS.md](AGENTS.md) / [README.md](README.md) / [设计索引](docs/design/blueprint-index.md) / [计划索引](docs/plans/README.md)。不批量加载历史。

## 已实现与边界

- 认证、Redis 多端会话、唯一 OWNER、每日成长、P0～P4 账号/资料审核及 U1～U4 整理已完成。公共注册关闭，OWNER 开户 USER/ADMIN，ADMIN 仅管理 USER，INSIDER 不自动有管理权；昵称/头像先审后发，历史资料仍待人工核对。
- 前端暂定方案二：白底墨色与个人小窗/空间、“开门小屋”SVG；保留首页特色效果。[设计](docs/design/frontend-v2.md) / [回滚](docs/operations/frontend-variants.md)。
- 妙替 R1～R7：本机词库/备份/分享/可选草稿、批处理/全文统计/有界定位/勾选/ZIP；500候选无默认排除，处理20文件/单个2 MiB/合计5 MiB；手选UTF-8/GBK/GB18030、输出UTF-8，源码全文替换。详见README/dev.10。
- 妙压 T1～T4：本地单图/批量体积卡位、JPEG DPI/COM、成功项ZIP；20张/60 MiB，单张20 MiB/4000万像素。T5换底/PDF/云预设已否决；未验边界见dev.11。
- **本机已有正式屋主**：不清空初始化，不在开发库跑 OWNER 夹具。凭据复用忽略的 `polyhouse/.idea/workspace.xml`，先 Inspect，不复制/输出；Redis 启动检查不是运行期监控。
- **后端依赖升级已否决**。AI、云模板未接入，3D/社交不在范围。P5 先做本地筹备，服务器与公网操作须具体授权。

## 最近交付与下一步

- 头像在浏览器裁剪为 160×160 WebP，JSON 提交，审核图片持久化到数据库。迁移、接口和回退见[资料审核](docs/operations/profile-moderation.md)。
- 本轮前端 335 项、后端普通 311 项、真实隔离 49 项通过，包含浏览器上传、审核与刷新显示。详见 dev.13。
- 头像审核改为单一预览入口，弹窗通过可留空、驳回必填；前端 336 项及隔离浏览器审核回归通过。
- 本机库已备份、试还原并重建。用户已核对 TiDB Cloud 的 21 条 CHECK，云端屋主初始化日志显示提交成功。[空库基线与屋主初始化](docs/operations/database-bootstrap.md)
- 妙压页眉分类统一为“图片处理”。本轮浏览器复核两工具下载与主要页面入口，前端 336 项、后端 368 项通过。
- 网页登录按电脑和移动浏览器发送 `PC` / `H5`，复用现有独立会话，隔离 HTTP 验证两端可同时在线。
- Render 候选后端已通过屋主登录和刷新恢复。空闲测试登录成功，体感约一分钟，日志确认新实例启动，触发原因待核对。GitHub Pages 接入及其余放行项目见[上线清单](docs/dev-log/launch-preparation.md)。

## 历史索引（按需）

| 阶段 | 已完成内容 |
| --- | --- |
| [dev.12](docs/dev-log/v1.0-dev.12.md) | 上线前质量收敛、前端回归与阶段合并 |
| [dev.11](docs/dev-log/v1.0-dev.11.md) | 妙压 T1～T4、取消保护、隔离集成与 Git 同步 |
| [dev.10](docs/dev-log/v1.0-dev.10.md) | 妙替 R1～R7、独立复审与主线同步 |
| [dev.9](docs/dev-log/v1.0-dev.9.md) | 文档治理、主线整合、质量优先与规范去重 |
| [dev.8](docs/dev-log/v1.0-dev.8.md) | P4 资料审核、U1～U4 整理与验收，已收口 |
| [dev.7](docs/dev-log/v1.0-dev.7.md) | P0～P3 账号安全/开户/管理 |
| [dev.6](docs/dev-log/v1.0-dev.6.md) | 方案二与品牌、备份回滚 |
| [dev.5](docs/dev-log/v1.0-dev.5.md) | 妙替、功能边界与有限容量/故障恢复 |
| [dev.4](docs/dev-log/v1.0-dev.4.md) / [dev.3](docs/dev-log/v1.0-dev.3.md) | 头像、首页、个人区域与成长 |
| [dev.2](docs/dev-log/v1.0-dev.2.md) / [dev.1](docs/dev-log/v1.0-dev.1.md) | 鉴权基础、成长与初始工程 |

旧清单见[入口快照](docs/dev-log/entry-before-context-governance.md)。阶段号、本地提交、历史测试不等于发布或已推送。
