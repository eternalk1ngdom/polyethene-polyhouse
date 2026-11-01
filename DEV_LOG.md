# 聚妙屋：当前状态与日志索引

更新时间：2026-11-01（Asia/Shanghai）。**v1.0 开发中，未正式发布**。

- 当前：[v1.0-dev.10 妙替本机词库](docs/dev-log/v1.0-dev.10.md)，先读“当前摘要”，详情按任务需要读取。
- Git：已完成成果于 2026-11-01 合入并同步 main `51cb4a0`，方案一备份保留；当前开发分支 `codex/replace-library`，新功能仅本地提交。[整合记录](docs/dev-log/v1.0-dev.9.md#主线整合与-github-同步2026-11-01) / [Git 入门](docs/learning/git-basics.md)。
- 当前批准计划：[妙替深化 R1](docs/plans/poly-replace-deepening.md#0-本轮批准范围与验收边界)；[文档工作流](docs/plans/context-efficiency.md)已交付；[计划索引](docs/plans/README.md)。
- 规则：[AGENTS.md](AGENTS.md)；配置/启动：[README.md](README.md)；[只读策划书索引](docs/design/blueprint-index.md)。不沿索引加载全部历史。

## 已实现与边界

- 认证、Redis 多端会话、退出/软注销、唯一 OWNER 与每日成长；P0～P4 受控开户/权限/改密/封禁解封/资料审核，以及 U1～U4 个人中心与工作台整理已完成。接口/迁移见 README 的运维链接。
- 公共注册关闭；OWNER 开户 USER/ADMIN。ADMIN 仅管理 USER，INSIDER 不自动有管理权；昵称/头像先审后发。真实历史资料仍待人工审核，不宣称全部已审。
- 前端暂定方案二：白底墨色与个人小窗/空间；统一“开门小屋”SVG，不自动更换整体风格。[设计](docs/design/frontend-v2.md) / [方案一备份与回滚](docs/operations/frontend-variants.md)。
- 妙替已支持小型文本批处理、AC 内核、预览与 ZIP；手动选 UTF-8/GBK/GB18030，统一导出 UTF-8，`.py` 按全文处理，具体限制见 README。云模板、妙压算法/业务、AI、Docker 编排未实现；3D/社交不在当前范围。
- **本机已有正式屋主**：不清空初始化记录，不在开发库运行含 OWNER 夹具的全量实例测试。真实验收使用明确隔离专项。
- 凭据与固定运行密钥复用 Git 忽略的 `polyhouse/.idea/workspace.xml`，先 Inspect，不重复索取/复制/输出。Redis 需独立服务，启动前检查不等于运行期监控。

## 最近验证与下一步

- R1 模型/校验/存储层独立验收完成：专项 22 项、全量前端 157 项、ESLint 与构建通过；尚未接入 UI，页面使用方式不变。规范整理已独立提交 `efda7b6`；详细证据与未验范围见 dev.10。
- 业务历史基线：`8d68bc9`，U4 于 2026-11-01 收口：普通后端 323 通过/103 实例跳过，前端 135 测试及构建/静态检查通过；四组隔离验收与页面组合验收详见 [dev.8 的 U4 记录](docs/dev-log/v1.0-dev.8.md#u4-局部综合回归收口2026-10-312026-11-01)。不代表新改动自动通过或已上线。
- 当前只做本地开发；**P5/上线/服务器操作搁置，后端依赖升级已否决，不再提议或询问**。恢复部署时需重新评估网络入口、人工资料核对、备份/恢复与发布；非标端口/审核不构成合规豁免。
- 用户已批准质量优先、按独立步骤推进；当前从 `51cb4a0` 新开 `codex/replace-library`，先整理规范，再只做妙替 R1（模型、纯校验、本地存储与测试），R2～R7 不在本轮实施。既有 Git 授权不延伸为新功能的自动推送、合并或发布。

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
