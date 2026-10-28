# 前端方案备份、切换与恢复

记录日期：2026-10-29。初次备份仅为本地 Git 操作；同日阶段收口时用户授权同步 GitHub，范围与恢复入口见文末。未创建正式标签或发布版本。

## 保存了什么

| 名称 | 用途 |
| --- | --- |
| `main` | 保持原有基线，本轮未移动 |
| `codex/backup-ui-v1-20261028` | 方案一的固定本地备份分支 |
| `codex/ui-v2-dribbble` | 方案二独立开发与验收分支；本轮交付在此 |
| `.local-backups/ui-v1-20261028.bundle` | 方案一完整 Git 历史的离线备份包，已通过 `git bundle verify` |

方案一基线为 `4f0fca1164695b62ca1160a800cf6e25749cc3c6`。备份名称保留任务开始时的 20261028；验收与记录日期为跨日后的 2026-10-29。方案一原有源码没有未提交改动；原有 `AGENTS.md` 工作区状态和 `.VSCodeCounter/` 不属于本轮，不纳入提交。`AGENTS.md` 另留个人本地副本于 `.local-backups/ui-v1-20261028/`，不以新版覆盖既有内容。

备份目录已在 `.gitignore` 排除。Git 分支/包保存代码和文档，不包含数据库、Redis、头像实体文件、依赖目录或被忽略的 IDEA 个人凭据。离线包仍位于本机同一磁盘，不等于异地灾备；远端备份需用户明确授权后再推送分支。

## 日常切换方案

以下命令从仓库根目录运行。**先检查工作区**：

```powershell
git status --short
```

有改动时，先将本次已验证且归属明确的修改提交，或明确选择路径暂存；不要覆盖用户文件，不运行 `reset --hard`、`clean -fd`，也不要为切换执行 `git add .`。如果 Git 提示文件会被覆盖，停止切换并先保存这些文件。本轮保留的 `AGENTS.md` 状态不能被自动丢弃。

切回方案一：

```powershell
git switch codex/backup-ui-v1-20261028
Set-Location polyhouse-web
npm run build
```

回到方案二（先回仓库根目录）：

```powershell
git switch codex/ui-v2-dribbble
Set-Location polyhouse-web
npm run build
```

开发服务器复用当前 5173 进程即可，切换后刷新页面；如果脚本或依赖清单将来变更，再按对应版本说明处理。分支切换保留两个方案及其提交，无需重写历史。不要在备份分支上继续常规开发；需要修改方案一时，从备份另开 `codex/` 分支。

这是**整仓代码版本切换**。本轮仅前端与文档变化，前后端接口及数据库结构相同，因此可以直接切换。后续若有接口或迁移变动，必须重新核对兼容性，不盲目把旧前端覆盖到新后端；数据库恢复需独立方案。

## 离线包恢复

检查包与分支指向：

```powershell
git bundle verify .local-backups/ui-v1-20261028.bundle
git bundle list-heads .local-backups/ui-v1-20261028.bundle
git rev-parse codex/backup-ui-v1-20261028
```

包中备份分支应指向上面的完整基线编号。本轮已从 bundle 克隆到独立的忽略目录 `.local-backups/ui-v1-restore-check-20261029/`，通过 `git fsck --full`，还原全部被跟踪文件并确认工作区干净、HEAD 与基线一致；未覆盖现有工作区，也未重复安装旧版依赖或启动旧版服务。

需要独立恢复时，在父目录选择**尚不存在的新目录**，从包克隆，例如：

```powershell
git clone --branch codex/backup-ui-v1-20261028 E:/project/java/polyethene-polyhouse/.local-backups/ui-v1-20261028.bundle E:/project/java/polyhouse-ui-v1-recovered
```

示例目标仅用于说明，本轮未创建。恢复后仍需本机 JDK/Node、依赖、数据库/Redis和私有运行配置；不要通过提交凭据解决配置缺失。备份包不是远端仓库，不应直接向它推送后续修改。

## 提交与验收定位

本轮方案二源码、设计说明与日志组成一次可独立验收的本地提交，具体编号以 `git log -1` 和最终汇报为准，不为记录自身编号再创建提交。[设计与接口清单](../design/frontend-v2.md)记录覆盖范围；[阶段日志](../dev-log/v1.0-dev.6.md)记录实际验证和未覆盖边界。

## 阶段收口后的远端保存

2026-10-29 用户授权推送。同步策略为将最终方案二 `codex/ui-v2-dribbble` 及固定备份 `codex/backup-ui-v1-20261028` 推送到 `origin` 的同名分支；不移动 `main`、不强推、不自动发布。上面的 `main`/仅本地描述保留首次备份时事实，同步范围以本节为准。

仓库：[eternalk1ngdom/polyethene-polyhouse](https://github.com/eternalk1ngdom/polyethene-polyhouse)。从 GitHub 新克隆时默认仍是 main，查看新版需显式选择 `codex/ui-v2-dribbble`；恢复旧版选择备份分支。已有本地仓库先遵守上面的工作区检查，再 `git fetch origin`、切换相应本地分支。远端内容不含被忽略的离线包和运行数据，不能代替数据库/头像备份。
