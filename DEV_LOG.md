# 聚妙屋：当前状态与日志索引

更新时间：2026-11-03（Asia/Shanghai）。**v1.0 开发中，未正式发布**。

- 当前：[v1.0-dev.11 妙压与集成准备](docs/dev-log/v1.0-dev.11.md)，先读“当前摘要”；历史实施证据按需读。
- Git：main 已在 `407a717` 收口妙替；原妙压 `wb/targetkb` 保持 `1c2a91c`。当前 `codex/targetkb-review-fixes` 是基于妙压的独立修复分支，尚未包含 main 后续改动；隔离工作树在主目录 `polyhouse/target/targetkb-review`。两个原工作树及已有修改均保留。
- 授权：用户批准独立修复、隔离试合及验证，正式合入 main/推送仍待另行确认，部署搁置。[本轮范围](docs/plans/target-kb-compression.md#02-合并前独立修复与隔离试合2026-11-03)。
- 入口：[AGENTS.md](AGENTS.md) / [README.md](README.md) / [设计索引](docs/design/blueprint-index.md) / [计划索引](docs/plans/README.md)。不批量加载历史。

## 已实现与边界

- 妙压 T1～T4：单图/批量本地处理，按目标体积搜索，JPEG DPI/COM；符合目标且无需转换格式时直接交付，批量成功项打包 ZIP。输入 20 张/合计 60 MiB、单张 20 MiB/40,000,000 像素；T5（换底/PDF/云预设）已否决。Safari/Firefox 真机、真实 AVIF 等未验边界见 dev.11，不承诺无限目录/图像容量。
- 妙替 R1～R7 已在 main 独立收口并同步 GitHub；本分支仍基于较早共同起点，不能冒充已带入妙替全部新代码。完整状态以 main 的 dev.10 和试合后入口为准。
- 账号/权限与 U1～U4 为既有主线成果；公共注册关闭，四角色与唯一 OWNER 保障不变，历史资料审核仍有人工边界。
- **本机已有正式屋主**：不清空初始化，不在开发库执行含 OWNER 夹具的全量实例测试。凭据复用忽略的私有预设，先 Inspect，不复制或输出。
- **P5/上线/服务器操作搁置；后端依赖升级已否决，不再提议或询问**。本轮无后端/SQL/依赖升级/发布，后续 GitHub 写操作仍按明确授权执行。

## 最近验证与下一步

- 本轮修复：用户任务 id 覆盖读取到 ZIP，防止取消/换参/卸载后的旧结果和旧收尾污染；头部预检迟到及 Worker 重试保护；DPI 提示准确。七份测试归档 `tests/target-kb/`，递归发现，新增生命周期 10 项。
- 修复分支 `npm test` 210 项、生命周期 10 项、ESLint/类型/构建与相关格式通过；真实 JPEG 原样/写 DPI、PNG 混合失败 ZIP、噪声图取消重试及五档实际宽度通过。尚不作为试合后验收，不重跑无影响 Maven/账号/全容量。
- 原妙压 200 项、T1～T4 浏览器与性能记录均为历史证据，见 dev.11；main 253 项及妙替验证仅为主线历史，不套用于本分支。
- 下一步：本地提交修复后，单独集成分支试合 main，整合双方日志/计划与技能说明，验证完整两工具与技能，再交用户确认。主目录已有报告与工具目录不纳入；不删除两个原工作树。

## 历史索引（按需）

| 阶段 | 内容 |
| --- | --- |
| [dev.9](docs/dev-log/v1.0-dev.9.md) | 文档/上下文与主线整理 |
| [dev.8](docs/dev-log/v1.0-dev.8.md) | P4、U1～U4 及既有隔离验收 |
| [dev.7](docs/dev-log/v1.0-dev.7.md) | P0～P3 权限/账号 |
| [dev.6](docs/dev-log/v1.0-dev.6.md) | 方案二与品牌 |
| [dev.5](docs/dev-log/v1.0-dev.5.md) | 妙替初始与有限容量 |
| [dev.4](docs/dev-log/v1.0-dev.4.md) / [dev.3](docs/dev-log/v1.0-dev.3.md) | 首页、头像、成长 |
| [dev.2](docs/dev-log/v1.0-dev.2.md) / [dev.1](docs/dev-log/v1.0-dev.1.md) | 鉴权基础 |

阶段号、历史测试和本地提交不等于正式发布；Git 状态与最终汇报为准。
