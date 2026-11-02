# 聚妙屋：当前状态与日志索引

更新时间：2026-11-03（Asia/Shanghai）。**v1.0 开发中，未正式发布**。

- 当前：[v1.0-dev.12 上线前质量收敛](docs/dev-log/v1.0-dev.12.md)，先读“当前摘要”；[Q0～Q5 计划与新窗口提示词](docs/plans/prelaunch-quality-hardening.md)。不批量加载历史。
- 基线：main/origin/main `3357a5f` 已收口妙替/妙压；新计划在 `codex/prelaunch-quality-plan` 本地交付，尚未合入/推送。采用 main 稳定线/dev 集成线/短期任务分支；dev 尚未创建，远端保护/CI 未配置。
- 本轮仅计划、必要规范与交接；下一会话先 Q0 本地准备、Q1 工具布局首个闭环。两个原目录、隔离工作树和已有其他修改保留，后续 GitHub/主线/部署等按具体授权执行。
- 入口：[AGENTS.md](AGENTS.md) / [README.md](README.md) / [设计索引](docs/design/blueprint-index.md) / [计划索引](docs/plans/README.md)。不批量加载历史。

## 已实现与边界

- 认证、Redis 多端会话、唯一 OWNER、每日成长、P0～P4 账号/资料审核及 U1～U4 整理已完成。公共注册关闭，OWNER 开户 USER/ADMIN，ADMIN 仅管理 USER，INSIDER 不自动有管理权；昵称/头像先审后发，历史资料仍待人工核对。
- 前端暂定方案二：白底墨色与个人小窗/空间、“开门小屋”SVG；保留首页特色效果。[设计](docs/design/frontend-v2.md) / [回滚](docs/operations/frontend-variants.md)。
- 妙替 R1～R7：本机词库、JSON 备份恢复（只替换全部）、分享、默认关闭的草稿/历史、AC 文本批处理、逐条全文统计/有界定位、文件勾选与 ZIP。500 候选、无默认排除；实际最多 20 文件、单个 2 MiB/合计 5 MiB；手选 UTF-8/GBK/GB18030、输出 UTF-8，`.py` 按全文处理。详见 README/dev.10。
- 妙压 T1～T4：本地单图/批量体积卡位、JPEG DPI/COM、成功项 ZIP；20 张/60 MiB、单张 20 MiB/4000 万像素。T5 换底/PDF/云预设已否决；Safari/Firefox 真机、真实 AVIF 等未验边界见 dev.11。
- **本机已有正式屋主**：不清空初始化，不在开发库跑 OWNER 夹具。凭据复用忽略的 `polyhouse/.idea/workspace.xml`，先 Inspect，不复制/输出；Redis 启动检查不是运行期监控。
- **P5/上线/服务器操作搁置；后端依赖升级已否决，不再提议或询问**。AI、Docker、云模板未接入，3D/社交不在范围；恢复部署须重新评估网络、审核、备份/恢复与发布。

## 最近验证与下一步

- 本轮只读梳理确认两工具宽度/断点差异、妙压部分容量文案口径及 CI 缺口；制定完整阶段计划，未改界面/后端，不宣称无 bug 或上线验收完成。
- 仅做文档链接/状态/契约一致性与差异检查，不运行 npm/Maven/真实实例。`ed074b6` 集成历史的 328 项、ESLint/构建/类型、技能 73 文件、两工具页面/下载通过；校验器 13 通过/1 权限跳过，Safari/Firefox/AVIF 等未验边界保留，见 dev.11，不当本轮新结果。
- 下一步：先核对本地规划与 main/dev 关系，留相同视口/缩放/状态的基线，统一工具页尺度，再逐步处理交互、后端与组合验证；重大视觉取舍让用户选择。P5/生产防滥用、备份恢复及部署缺口只列计划，不自动恢复实施。
- 妙压集成已结束，原妙压分支/目录保留。该规划尚未实施，不自动推进全部步骤、清理目录或纳入其他修改。

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

重复完成清单与旧验证表见[整理前入口快照](docs/dev-log/entry-before-context-governance.md)。阶段编号、本地提交、历史测试都不等于正式发布或 GitHub 已同步。
