# 聚妙屋：当前状态与日志索引

更新时间：2026-11-03（Asia/Shanghai）。**v1.0 开发中，未正式发布**。

- 当前：[v1.0-dev.11 妙压与隔离集成](docs/dev-log/v1.0-dev.11.md)，先读“当前摘要”，历史证据按需读。
- Git：用户确认后，`407a717..ed074b6` 共 17 个提交已快进合入 main 并同步 GitHub；集成分支 `codex/targetkb-integration` 已推送。原妙压 `wb/targetkb` 保持 `1c2a91c`。另以纯文档提交记录同步事实，最新编号以 Git/最终汇报为准。
- 两个原工作树、隔离工作树和已有修改均保留；本次授权覆盖集成分支推送与 main 合入/同步，不含部署或目录清理。[本轮范围](docs/plans/target-kb-compression.md#02-合并前独立修复与隔离试合2026-11-03)。
- 入口：[AGENTS.md](AGENTS.md) / [README.md](README.md) / [设计索引](docs/design/blueprint-index.md) / [计划索引](docs/plans/README.md)。不批量加载历史。

## 已实现与边界

- 认证、Redis 多端会话、唯一 OWNER、每日成长、P0～P4 账号/资料审核及 U1～U4 整理已完成。公共注册关闭，OWNER 开户 USER/ADMIN，ADMIN 仅管理 USER，INSIDER 不自动有管理权；昵称/头像先审后发，历史资料仍待人工核对。
- 前端暂定方案二：白底墨色与个人小窗/空间、“开门小屋”SVG；保留首页特色效果。[设计](docs/design/frontend-v2.md) / [回滚](docs/operations/frontend-variants.md)。
- 妙替 R1～R7：本机词库、JSON 备份恢复（只替换全部）、分享、默认关闭的草稿/历史、AC 文本批处理、逐条全文统计/有界定位、文件勾选与 ZIP。500 候选、无默认排除；实际最多 20 文件、单个 2 MiB/合计 5 MiB；手选 UTF-8/GBK/GB18030、输出 UTF-8，`.py` 按全文处理。详见 README/dev.10。
- 妙压 T1～T4：本地单图/批量体积卡位、JPEG DPI/COM、成功项 ZIP；20 张/60 MiB、单张 20 MiB/4000 万像素。T5 换底/PDF/云预设已否决；Safari/Firefox 真机、真实 AVIF 等未验边界见 dev.11。
- **本机已有正式屋主**：不清空初始化，不在开发库运行含 OWNER 夹具的全量实例测试。凭据复用忽略的 `polyhouse/.idea/workspace.xml`，先 Inspect，不复制/输出；Redis 启动检查不是运行期监控。
- **P5/上线/服务器操作搁置；后端依赖升级已否决，不再提议或询问**。AI、Docker、云模板未接入，3D/社交不在范围；恢复部署须重新评估网络、审核、备份/恢复与发布。

## 最近验证与下一步

- 独立修复 `db8d909`：210 项测试、ESLint/构建/类型及相关格式通过；真实 DPI/原样字节、部分失败 ZIP、取消重试与五档宽度通过。这是修复分支证据，不能冒充试合验收。
- 隔离集成验收：全量前端 328 项、ESLint/构建/类型通过，技能 73 文件一致；校验器 13 通过/1 权限跳过。两工具实际下载、手选/定位、DPI/COM/部分失败 ZIP、鼠标/Tab 与八档宽度通过，详情及未验边界见 dev.11。
- main 妙替 253 项与 U4 等均为历史基线，见对应阶段，不冒充本轮通过。无后端/SQL/依赖升级，不重跑无影响的 Maven、账号或全容量测试。
- 下一步：妙压集成与 Git 同步已收口，后续任务待选；不自动发布或清理目录，不纳入其他任务修改。新增 GitHub 写操作及部署仍按授权范围确认。

## 历史索引（按需）

| 阶段 | 已完成内容 |
| --- | --- |
| [dev.10](docs/dev-log/v1.0-dev.10.md) | 妙替 R1～R7、独立复审与主线同步 |
| [dev.9](docs/dev-log/v1.0-dev.9.md) | 文档治理、主线整合、质量优先与规范去重 |
| [dev.8](docs/dev-log/v1.0-dev.8.md) | P4 资料审核、U1～U4 整理与验收，已收口 |
| [dev.7](docs/dev-log/v1.0-dev.7.md) | P0～P3 账号安全/开户/管理 |
| [dev.6](docs/dev-log/v1.0-dev.6.md) | 方案二与品牌、备份回滚 |
| [dev.5](docs/dev-log/v1.0-dev.5.md) | 妙替、功能边界与有限容量/故障恢复 |
| [dev.4](docs/dev-log/v1.0-dev.4.md) / [dev.3](docs/dev-log/v1.0-dev.3.md) | 头像、首页、个人区域与成长 |
| [dev.2](docs/dev-log/v1.0-dev.2.md) / [dev.1](docs/dev-log/v1.0-dev.1.md) | 鉴权基础、成长与初始工程 |

重复完成清单与旧验证表见[整理前入口快照](docs/dev-log/entry-before-context-governance.md)。阶段编号、本地提交、历史测试都不等于正式发布或 GitHub 已同步。
