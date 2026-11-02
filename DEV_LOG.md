# 聚妙屋：当前状态与日志索引

更新时间：2026-11-03（Asia/Shanghai）。**v1.0 开发中，未正式发布**。

- 当前：[v1.0-dev.12 上线前质量收敛](docs/dev-log/v1.0-dev.12.md)，先读“当前摘要”；[Q0～Q5 计划与新窗口提示词](docs/plans/prelaunch-quality-hardening.md)。不批量加载历史。
- 基线：main/origin/main `3357a5f` 已收口妙替/妙压；规划 `e1c4f31` 已完整纳入新建本地 dev；`codex/fix-tool-layout` 承载实施提交；dev/任务分支已推送 GitHub，未合 main/dev。远端保护/CI 未配置。
- Q0 与 Q1 首个工具布局闭环本地完成：两工具统一 1320px/900px 断点及介绍区尺度，修复妙压长文件名撑宽；保留首页粉色光晕。两个原目录、隔离工作树与其他修改保留；远端/主线/部署另按授权。
- 入口：[AGENTS.md](AGENTS.md) / [README.md](README.md) / [设计索引](docs/design/blueprint-index.md) / [计划索引](docs/plans/README.md)。不批量加载历史。

## 已实现与边界

- 认证、Redis 多端会话、唯一 OWNER、每日成长、P0～P4 账号/资料审核及 U1～U4 整理已完成。公共注册关闭，OWNER 开户 USER/ADMIN，ADMIN 仅管理 USER，INSIDER 不自动有管理权；昵称/头像先审后发，历史资料仍待人工核对。
- 前端暂定方案二：白底墨色与个人小窗/空间、“开门小屋”SVG；保留首页特色效果。[设计](docs/design/frontend-v2.md) / [回滚](docs/operations/frontend-variants.md)。
- 妙替 R1～R7：本机词库、JSON 备份恢复（只替换全部）、分享、默认关闭的草稿/历史、AC 文本批处理、逐条全文统计/有界定位、文件勾选与 ZIP。500 候选、无默认排除；实际最多 20 文件、单个 2 MiB/合计 5 MiB；手选 UTF-8/GBK/GB18030、输出 UTF-8，`.py` 按全文处理。详见 README/dev.10。
- 妙压 T1～T4：本地单图/批量体积卡位、JPEG DPI/COM、成功项 ZIP；20 张/60 MiB、单张 20 MiB/4000 万像素。T5 换底/PDF/云预设已否决；Safari/Firefox 真机、真实 AVIF 等未验边界见 dev.11。
- **本机已有正式屋主**：不清空初始化，不在开发库跑 OWNER 夹具。凭据复用忽略的 `polyhouse/.idea/workspace.xml`，先 Inspect，不复制/输出；Redis 启动检查不是运行期监控。
- **P5/上线/服务器操作搁置；后端依赖升级已否决，不再提议或询问**。AI、Docker、云模板未接入，3D/社交不在范围；恢复部署须重新评估网络、审核、备份/恢复与发布。

## 最近验证与下一步

- 本轮构建/类型、ESLint、四文件 Prettier、两工具 lifecycle 21/21 通过；实际检查空态/长名称/成功/失败、鼠标/Tab/弹窗、导航页脚与首页搜索。
- Chromium 覆盖 320/390/768/1024/1440、699/700/702、899/900/902px；701/901 请求实际取整为 702/902，精确宽度未验。截图/尺寸见忽略目录 `.local-backups/prelaunch-q1-20261103/` 及阶段日志。
- 下一步：确认交付后继续 Q1 其余页面；Q2～Q5 未实施。Maven/真实实例/完整下载字节、Safari/Firefox/真机/AVIF 未验，历史 328 项不当本轮通过。
- main 不变，dev 目前仅含规划；实施提交在任务分支。远端晋级/CI/P5/部署/清理另按具体授权。

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
