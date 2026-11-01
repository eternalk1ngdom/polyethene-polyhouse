# 聚妙屋：当前状态与日志索引

更新时间：2026-11-01（Asia/Shanghai）。**v1.0 开发中，未正式发布**。

- 当前：[v1.0-dev.11 妙压开工准备](docs/dev-log/v1.0-dev.11.md)，先读“当前摘要”，详情按任务需要读取。妙替 R1/R2 在 `codex/replace-library`（dev.10），本分支不含。
- Git：双工作树并行——妙替 `E:/project/java/polyethene-polyhouse`（`codex/replace-library`），妙压 `E:/project/java/polyhouse-press`（`wb/targetkb`，基线 `efda7b6`）；共享同一 `.git`，新功能只本地提交。[Git 入门](docs/learning/git-basics.md)。
- 当前批准计划：[妙压体积卡位](docs/plans/target-kb-compression.md)（T1～T4 已完成，T5 未批准）；妙替深化在另一分支；[计划索引](docs/plans/README.md)。
- 规则：[AGENTS.md](AGENTS.md)；配置/启动：[README.md](README.md)；[只读策划书索引](docs/design/blueprint-index.md)。不沿索引加载全部历史。

## 已实现与边界

- 认证、Redis 多端会话、退出/软注销、唯一 OWNER 与每日成长；P0～P4 受控开户/权限/改密/封禁解封/资料审核，以及 U1～U4 个人中心与工作台整理已完成。接口/迁移见 README 的运维链接。
- 公共注册关闭；OWNER 开户 USER/ADMIN。ADMIN 仅管理 USER，INSIDER 不自动有管理权；昵称/头像先审后发。真实历史资料仍待人工审核，不宣称全部已审。
- 前端暂定方案二：白底墨色与个人小窗/空间；统一“开门小屋”SVG，不自动更换整体风格。[设计](docs/design/frontend-v2.md) / [方案一备份与回滚](docs/operations/frontend-variants.md)。
- 妙替已支持小型文本批处理、AC 内核、预览与 ZIP；手动选 UTF-8/GBK/GB18030，统一导出 UTF-8，`.py` 按全文处理，具体限制见 README。妙压已开放单图与批量压缩（本地处理、按目标体积二分、小图补注释段、可选 DPI；批量最多 20 张/60MB、可逐项移除，失败不产出半成品，成功后打包 ZIP），不做 PNG 输出、PDF 与证件照换底；云模板、AI、Docker 编排未实现；3D/社交不在当前范围。
- **本机已有正式屋主**：不清空初始化记录，不在开发库运行含 OWNER 夹具的全量实例测试。真实验收使用明确隔离专项。
- 凭据与固定运行密钥复用 Git 忽略的 `polyhouse/.idea/workspace.xml`，先 Inspect，不重复索取/复制/输出。Redis 需独立服务，启动前检查不等于运行期监控。

## 最近验证与下一步

- 妙压 T4 试用修复：修好"压缩后待压缩缩略图变破图"（对象地址被 `invalidate()` 误撤）、补上待选区逐项移除与清空、纠正"未开始就显示正在处理第 1/N 张"；`outputs` 改用稳定 id，删除后不再串图；181 测试、ESLint 与构建通过，证据见 dev.11。
- 妙压 T4：批量与 ZIP 端到端通过（3 张 2.88MB → 643.6KB，2.9 秒，ZIP 659,433 字节三项；混入 21MB 大图时成功 2 失败 1，ZIP 只含成功项；取消回到"等待"不产出 ZIP；390px 无溢出）；181 测试、ESLint 与构建通过，证据见 dev.11。
- 妙压 T3：页面端到端通过（1.33MB 大图 → 119.9KB/缩 2 档/33 次 5.3 秒；小图补到 60.0KB；取消与失败均不产出文件；21MB 选图即拦；390px 无溢出；Tab 12 个落点全可见焦点，修好开关无焦点）；175 测试、ESLint 与构建通过。工具已标为可用，证据见 dev.11。
- 业务历史基线：`8d68bc9`，U4 于 2026-11-01 收口：普通后端 323 通过/103 实例跳过，前端 135 测试及构建/静态检查通过；四组隔离验收与页面组合验收详见 [dev.8 的 U4 记录](docs/dev-log/v1.0-dev.8.md#u4-局部综合回归收口2026-10-312026-11-01)。不代表新改动自动通过或已上线。
- 当前只做本地开发；**P5/上线/服务器操作搁置，后端依赖升级已否决，不再提议或询问**。恢复部署时需重新评估网络入口、人工资料核对、备份/恢复与发布；非标端口/审核不构成合规豁免。
- 质量优先、按独立步骤推进：妙替 R2 在 `codex/replace-library` 实施中；妙压 T4 已收口，T5（证件照换底/PDF/云预设）与内核搜索次数优化需另行批准。既有 Git 授权不延伸为新功能的自动推送、合并或发布。

## 历史索引（按需）

| 阶段 | 已完成内容 |
| --- | --- |
| [dev.9](docs/dev-log/v1.0-dev.9.md) | 文档治理、主线整合、质量优先与规范去重 |
| [dev.8](docs/dev-log/v1.0-dev.8.md) | P4 资料审核、U1～U4 整理与验收，已收口 |
| [dev.7](docs/dev-log/v1.0-dev.7.md) | P0～P3 账号安全/开户/管理 |
| [dev.6](docs/dev-log/v1.0-dev.6.md) | 方案二与品牌、备份回滚 |
| [dev.5](docs/dev-log/v1.0-dev.5.md) | 妙替、功能边界与有限容量/故障恢复 |
| [dev.4](docs/dev-log/v1.0-dev.4.md) / [dev.3](docs/dev-log/v1.0-dev.3.md) | 头像、首页、个人区域与成长 |
| [dev.2](docs/dev-log/v1.0-dev.2.md) / [dev.1](docs/dev-log/v1.0-dev.1.md) | 鉴权基础、成长与初始工程 |

重复完成清单与旧验证表见[整理前入口快照](docs/dev-log/entry-before-context-governance.md)。阶段编号、本地提交、历史测试都不等于正式发布或 GitHub 已同步。
