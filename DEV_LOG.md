# 聚妙屋：当前状态与日志索引

更新时间：2026-11-03（Asia/Shanghai）。**v1.0 开发中，未正式发布**。

- 当前：[v1.0-dev.12 上线前质量收敛](docs/dev-log/v1.0-dev.12.md)，先读“当前摘要”；[Q0～Q5 计划与新窗口提示词](docs/plans/prelaunch-quality-hardening.md)。不批量加载历史。
- 基线：main/origin/main `3357a5f`；dev为规划 `e1c4f31`；Q0/Q1及同步记录在 `codex/fix-tool-layout`（`fb32755`）。两分支已推送，尚未PR/合并；保护/CI未配置。
- Q0/Q1首个布局闭环完成：统一1320px/900px断点，修复妙压长名称撑宽。原目录/工作树和其他修改保留。
- 筹备：[服务器/续费/武汉个人备案讨论](docs/dev-log/launch-preparation.md)持续更新，倾向109元普通4核4G、免费朋友测试；未采购/备案/恢复P5。本地文档分支 `codex/docs-launch-preparation`。
- 入口：[AGENTS.md](AGENTS.md) / [README.md](README.md) / [设计索引](docs/design/blueprint-index.md) / [计划索引](docs/plans/README.md)。不批量加载历史。

## 已实现与边界

- 认证、Redis 多端会话、唯一 OWNER、每日成长、P0～P4 账号/资料审核及 U1～U4 整理已完成。公共注册关闭，OWNER 开户 USER/ADMIN，ADMIN 仅管理 USER，INSIDER 不自动有管理权；昵称/头像先审后发，历史资料仍待人工核对。
- 前端暂定方案二：白底墨色与个人小窗/空间、“开门小屋”SVG；保留首页特色效果。[设计](docs/design/frontend-v2.md) / [回滚](docs/operations/frontend-variants.md)。
- 妙替 R1～R7：本机词库/备份/分享/可选草稿、批处理/全文统计/有界定位/勾选/ZIP；500候选无默认排除，处理20文件/单个2 MiB/合计5 MiB；手选UTF-8/GBK/GB18030、输出UTF-8，源码全文替换。详见README/dev.10。
- 妙压 T1～T4：本地单图/批量体积卡位、JPEG DPI/COM、成功项ZIP；20张/60 MiB，单张20 MiB/4000万像素。T5换底/PDF/云预设已否决；未验边界见dev.11。
- **本机已有正式屋主**：不清空初始化，不在开发库跑 OWNER 夹具。凭据复用忽略的 `polyhouse/.idea/workspace.xml`，先 Inspect，不复制/输出；Redis 启动检查不是运行期监控。
- **P5/上线/服务器操作搁置；后端依赖升级已否决，不再提议或询问**。AI、Docker、云模板未接入，3D/社交不在范围；恢复部署须重新评估网络、审核、备份/恢复与发布。

## 最近验证与下一步

- 当前：`codex/feat-tool-progressive-ui` 保留筹备提交；首页/工具渐进展示已交付（前轮332项）。体验参考已按需索引，文件/目录入口已合区，Q1登录短屏关闭焦点裁切已修复。
- 本轮：构建/类型、ESLint、变更文件格式检查通过；入口相关24/24测试、44组工具布局、原生目录及ZIP路径实测通过。登录修后18组布局、短屏Tab/关闭通过；证据见阶段日志与忽略目录 `.local-backups/frontend-reference-20261103/`。
- 请求701/901/481实际702/902/482，精确宽度未验；Safari/Firefox真机、系统文件拖入未验。下一步Q1个人/管理页及全面Q2～Q5。
- main不变，dev仅含规划；实施留任务分支。远端晋级/CI/P5/部署/清理另按授权。

## 历史索引（按需）

| 阶段 | 已完成内容 |
| --- | --- |
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
