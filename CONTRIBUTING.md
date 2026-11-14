# 参与聚妙屋

[项目首页](README.md) · [首次运行](docs/operations/getting-started.md) · [验证入口](docs/operations/verification.md)

## 先了解正在维护的内容

从 [DEV_LOG.md](DEV_LOG.md) 了解当前阶段与发布基线，再读 [AGENTS.md](AGENTS.md)。它是本项目唯一长期工程约束，包含角色、三层隔离、纯算法、安全、UI、验证和提交要求。本文说明贡献流程，具体工程规则以该文件为准。设计细节通过[只读索引](docs/design/blueprint-index.md)按需读取，计划和完成事实分别在 `docs/plans/` 与 `docs/dev-log/`。

当前没有项目许可证文件。贡献前请与维护者确认用途和授权安排。第三方技能的许可证仅覆盖对应第三方内容。

## 报告问题或提需求

在[项目 Issues](https://github.com/eternalk1ngdom/polyethene-polyhouse/issues)先搜索相近问题，再提交以下信息。

- 正在使用的站点完整路径或本地提交、操作系统、浏览器和工具版本。
- 最小复现步骤，预期结果与实际结果，失败时间及脱敏提示。
- 可公开的小样本，发生概率，以及已尝试的检查。

发现漏洞时先通过维护者可提供的私密渠道联系，或在项目开启 GitHub 私密漏洞报告后使用该入口。公开 Issue 只描述需要私下联系，不包含利用细节、密码、Token、数据库连接串或用户资料。截图和日志先检查脱敏。

## 准备开发环境

新机器按[首次运行](docs/operations/getting-started.md)准备。已有本机预设先用[配置参考](docs/operations/local-configuration.md)的 Inspect 入口检查，避免覆盖正式屋主或重复生成密钥。

原前端模板建议的开发工具仍可选用。推荐 VS Code 配合 [Vue Official](https://marketplace.visualstudio.com/items?itemName=Vue.volar)，已有 Vetur 时避免同时接管 Vue 文件。`.vue` 的类型检查使用项目现有 `vue-tsc`。Chromium 浏览器可用 [Vue Devtools](https://devtools.vuejs.org/guide/installation)、[自定义对象格式化](https://developer.chrome.com/docs/devtools/console/format-style#custom-formatters)，Firefox 可用 [Vue Devtools](https://addons.mozilla.org/en-US/firefox/addon/vue-js-devtools/)及其[自定义格式化设置](https://fxdx.dev/firefox-devtools-custom-object-formatters/)。这些是调试辅助，应用启动按指南所需工具即可。Vite 配置参考[官方文档](https://vite.dev/config/)。

## 建立任务分支

有仓库写权限的协作者在工作区干净时从最新 `dev` 建分支。

```powershell
git fetch origin
git switch dev
git merge --ff-only origin/dev
git switch -c codex/你的任务名
```

如果本地 `dev` 不能快进，先核对独有提交。已有未提交修改要保留并确认归属。无仓库写权限时 fork 仓库，将原仓库作为 `upstream`，从 `upstream/dev` 建分支，最后向原仓库的 `dev` 提 PR。

## 找到代码和测试

| 改动 | 主要入口 |
| --- | --- |
| 登录、资料、开户与管理 | 后端 `controller/` → `service/` → `mapper/`，入参/出参在 `model/dto/`、`model/vo/`；前端 `api/`、`stores/`、`views/` |
| 妙替、妙压 | 前端 `src/config/toolRegistry.ts`、`router/`、对应页面、`workers/` 与 `core/` |
| 后端业务测试 | `polyhouse/src/test/java/com/polyethene/polyhouse/user/`；公共与系统测试见验证指南 |
| 前端业务测试 | `polyhouse-web/tests/user/`、`replace/`、`target-kb/` |
| 运维或配置 | `polyhouse/scripts/`、`polyhouse/sql/`、`docs/operations/` |

先搜索定义和调用，核对运行入口、数据格式和失败路径，再选最小改动。例如修复妙替计数，先找到 `core/` 的计数逻辑及 `tests/replace/` 覆盖，补最小复现并执行受影响测试和前端构建；涉及页面交互时按工程约定实际验 UI。

## 验证并提交

按 [AGENTS.md](AGENTS.md) 选择范围。[验证指南](docs/operations/verification.md)保留普通、隔离、浏览器和容量恢复的命令。新克隆的前端使用 `npm ci`，`npm run lint:check` 只检查，`npm run lint`、`format` 会修改文件。

提交前检查工作区和差异，按路径暂存与当前任务有关的完整改动。提交标题和正文以简洁中文说明问题和结果，常用类型可保留 `fix(user)`、`docs` 等。独立闭环同步当前阶段日志。变更依赖、安全边界或公共结构时先按工程约定讨论方案。

## 提交 PR 和发布

有授权后推送任务分支，目标选择 `dev`。PR 应让未读过聊天的协作者也能理解。

- 具体问题、触发方式以及修改后的行为。
- 影响范围、兼容情况，涉及界面时的核对结果。
- 实际执行的命令、结果及跳过或未验原因。
- 数据或配置变更的回退方式，相关 Issue 或计划。

候选验收后再由 `dev` 晋级到 `main`。当前项目 Pages 会随 `main` 推送构建，根站由独立仓库固定源码提交，Render 的实际部署记录见[上线清单](docs/dev-log/launch-preparation.md)。GitHub PR 测试门禁与独立审批以仓库实际配置为准，Pages 发布成功仅说明对应构建和发布完成。推送、PR、主线合并及部署遵循工程约定的授权范围。
