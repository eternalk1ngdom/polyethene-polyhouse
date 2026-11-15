# 聚妙屋：当前状态与日志索引

更新时间：2026-11-15（Asia/Shanghai）。**v1.0 开发中。**

- 当前：[dev.14 框架优化](docs/dev-log/v1.0-dev.14.md)已通过 [PR #4](https://github.com/eternalk1ngdom/polyethene-polyhouse/pull/4) 合入 dev `be1a108`：前端 346 项/构建、后端编译/34 项相关测试通过。[评估](docs/design/framework-assessment.md)保留分层，F5 跳过；main 与部署未更新。
- 基线：候选版本经试用后由 dev `9606cd4` 合入 main `aaa4ddc`。Render 已部署该提交，用户确认公开前端可登录且账号列表正常。
- Q0/Q1布局完成：统一1320px/900px断点，修复妙压长名称撑宽；原目录/工作树及其他修改保留。
- 云端：[上线验收清单](docs/dev-log/launch-preparation.md)。采用 Render、TiDB Cloud 与 Upstash。
- 入口：[AGENTS.md](AGENTS.md) / [README.md](README.md) / [设计索引](docs/design/blueprint-index.md) / [计划索引](docs/plans/README.md)。不批量加载历史。

## 已实现与边界

- 认证、Redis 多端会话、唯一 OWNER、每日成长、P0～P4 账号/资料审核及 U1～U4 整理已完成。公共注册关闭，屋主可创建 USER、ADMIN、INSIDER，TiDB 已核对新版开户约束。ADMIN 仅管理 USER，昵称/头像先审后发，历史资料仍待人工核对。
- 前端视觉基线 1.0（原方案二）：白底墨色与个人小窗/空间、“开门小屋”SVG；保留首页特色效果。仅统一称呼，方案一及选型历史保留。[设计](docs/design/frontend-v2.md) / [回滚](docs/operations/frontend-variants.md)。
- 妙替 R1～R7、妙压 T1～T4 已完成：浏览器本机文本替换、词库/草稿/分享和图片体积卡位/DPI/ZIP。完整编码、容量和恢复规则见[使用指南](docs/guides/features.md)，历史验收见 dev.10/11；T5换底/PDF/云预设已否决。
- **本机已有正式屋主**：不清空初始化，不在开发库跑 OWNER 夹具。凭据复用忽略的 `polyhouse/.idea/workspace.xml`，先 Inspect，不复制/输出；Redis 启动检查不是运行期监控。
- **后端依赖版本继续冻结**，本次仅批准内嵌 Tomcat 升至 10.1.61，候选镜像本地验证通过。AI、云模板未接入，3D/社交不在范围。服务器与公网操作须具体授权。

## 既有交付（详细验收见 dev.13）

- 头像在浏览器裁剪为 160×160 WebP，JSON 提交，审核图片持久化到数据库。迁移、接口和回退见[资料审核](docs/operations/profile-moderation.md)。
- 待审昵称与头像统一单一预览入口，通过理由可空、驳回必填；历史浏览器上传、审核与刷新验收及各批测试数字见[dev.13](docs/dev-log/v1.0-dev.13.md)。
- 开户详情可复制账号/一次性密码，密码默认遮住；账号列表有只读详情，凭据遗失按原流程重置。本机备份/试还原/重建、TiDB 21 条 CHECK 及屋主提交见[数据库运维](docs/operations/database-bootstrap.md)。
- 妙压页眉为“图片处理”，两工具下载与主要入口的历史浏览器验收见 dev.13。
- 网页登录按电脑和移动浏览器发送 `PC` / `H5`，复用现有独立会话，隔离 HTTP 验证两端可同时在线。
- Render 登录/刷新、约一分钟的空闲唤醒记录、GitHub Pages 接入及未验边界见[上线清单](docs/dev-log/launch-preparation.md)；具体唤醒触发请求未确认。

## 历史索引（按需）

| 阶段 | 已完成内容 |
| --- | --- |
| [dev.13](docs/dev-log/v1.0-dev.13.md) | 头像入库、云端适配及 Render 与 GitHub Pages 发布验收 |
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
