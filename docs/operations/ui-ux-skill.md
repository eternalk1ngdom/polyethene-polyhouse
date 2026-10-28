# UI/UX Pro Max：安装记录、使用与恢复

首次安装：2026-10-28（Asia/Shanghai）。本记录只管理开发辅助技能，不是第二份工程约束；项目契约仍以根目录 [AGENTS.md](../../AGENTS.md) 为准。

## 本次安装事实

| 项目 | 实际结果 |
| --- | --- |
| 来源 | [nextlevelbuilder/ui-ux-pro-max-skill](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) |
| 版本 | [v2.15.0](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill/releases/tag/v2.15.0) |
| 固定提交 | `a38d04c3d5c298c851dbe5e6ee1965ee3de42cb5`，后续不自动追随 main/latest |
| 范围 | 仅本仓库 `.agents/skills/ui-ux-pro-max/`，在 E 盘；未安装到用户全局目录 |
| 文件 | 73 个文件，约 3.4 MiB；全部内容及 SHA-256 见[固定清单](ui-ux-skill-lock.json) |
| 运行环境 | 已有 Python 3.13.12；不安装 Python、npm 全局工具或网站运行依赖 |
| 许可 | 目录中保留上游 MIT `LICENSE` |
| 变更前基线 | Git 完整提交记录在清单 `preInstallHead`，安装对应提交见 Git 历史和当轮汇报 |

安装采用内置 `skill-installer` 的安全下载/解压、目录验证和复制辅助函数，按精确提交获取源码；未执行第三方 npm 安装器。GitHub 公共 API 限流后通过只读 `git ls-remote` 核对 tag，下载不转发私人 GitHub Token；暂存目录为 Git 忽略的 `polyhouse/target/ui-ux-install/`，不使用 C 盘 npm 缓存。

只安装上游 `.claude/skills/ui-ux-pro-max` 的完整主技能目录，不带入其他配套技能或整套开发仓库。数据、检索代码、参考文件和上游测试均保持原字节；仅将 `SKILL.md` 的 Claude 插件路径改成 Codex 项目路径，补充从其他工作目录运行时应解析绝对路径及先读取仓库契约的说明。另加入许可、局部 Python 缓存忽略规则和保留原字节的 Git 换行设置；改动类别、原始与安装后散列均在清单中。

本次没有修改前后端业务代码、依赖版本、Vite 配置、运行密钥、数据库、Redis、头像目录或系统环境，也没有生成 `design-system/` 文件夹。

## 使用方式

Codex 按[官方发现规则](https://learn.chatgpt.com/docs/build-skills)读取项目 `.agents/skills/`。下一轮可明确说：

> 使用 ui-ux-pro-max 审查聚妙屋首页的图标、字体和间距，遵守 AGENTS.md，保留既有技术栈与已确定的视觉方向，先给建议，暂不改代码。

文件安装与检索已验证；当前回合不能预先证明下一轮是否会自动选择该技能。如技能选择器未更新，重启 Codex 后再试。安装位置符合本项目及官方项目级发现路径，不依赖 Claude 的环境变量。

人工从仓库根目录查询可执行：

```powershell
python -B .agents/skills/ui-ux-pro-max/scripts/search.py "image" --domain icons
python -B .agents/skills/ui-ux-pro-max/scripts/search.py "form validation" --stack vue
```

它返回本地资料中的候选建议，不是运行时 UI 库或训练后的新模型。使用时按实际任务查询；安装本身不代表同意改页面、安装其他依赖或重建项目风格。需要保留设计讨论时使用现有设计文档/日志；`--persist` 会创建设计系统文档，使用前先核对是否确实需要，尤其不能把其生成的 MASTER 当作工程契约替代品。

## 只读完整性检查

从仓库根目录执行：

```powershell
.\polyhouse\scripts\Check-UiUxSkill.ps1
```

脚本只检查来源字段、固定路径、文件是否齐全、SHA-256 是否一致以及是否出现未登记文件，不联网、不执行技能、不读取私有预设。首次结果为 `FilesVerified=73`。Python 字节码缓存不属于安装清单，通过目录内 `.gitignore` 排除；日常查询加 `-B` 可避免生成缓存。

散列用于发现意外变化，不代表第三方代码已经过完整安全审计，也不能替代对更新内容的审查。不要为了让检查通过而直接重算清单覆盖旧散列；先用 Git 差异定位原因。

## 可逆停用与恢复

只想暂停技能时，无需删除代码或清理任何数据库。确认没有待保留的技能修改，并在仓库根目录重命名这个精确入口文件：

```powershell
Rename-Item -LiteralPath '.agents/skills/ui-ux-pro-max/SKILL.md' -NewName 'SKILL.md.disabled'
```

关闭当前任务中的技能使用，重启 Codex 后不再发现该入口。要恢复：

```powershell
Rename-Item -LiteralPath '.agents/skills/ui-ux-pro-max/SKILL.md.disabled' -NewName 'SKILL.md'
.\polyhouse\scripts\Check-UiUxSkill.ps1
```

停用期间完整性检查会按预期报告缺少入口，不应修改清单“修复”它。这里仅重命名一个文件，不递归删除 `.agents`，也不调整全局配置。

如要完整撤回安装，先查看 Git 状态、保留后来产生的修改，再通过 Git 历史找到本次安装提交，在确认范围后执行 `git revert`；该命令新增撤销记录，不改写历史。若出现冲突，应停止并核对，不运行 `git reset --hard` 或 `git clean`。重新启用已提交版本时，可从对应 Git 提交恢复这个精确技能目录及清单，避免混用不同版本的数据与脚本。

## 更新流程与本次验证

未来更新时先读取本记录和清单，检查本地差异；把新版本放入独立临时目录，核对来源/许可、脚本和技能指令，再比较并验证，最后同步清单与当前阶段日志并本地提交。不对当前目录直接运行 `--force` 或未经审查的 latest 更新；不同版本不能混装。

本次实际通过：73 个文件的完整性检查；临时额外文件被检查器拒绝，移除该探针后恢复通过；图标查询 1 条、Vue 查询 1 条、交互查询 3 条有效结果；设计系统查询返回有效数据且 `persistence=null`；上游 `validate_data.py` 校验 12 个领域文件、22 个技术栈文件及推理表。上述查询均不传私有数据，不使用 `--persist`。前后端规定构建、PowerShell 语法与文档链接检查通过；未重跑历史业务测试或含 OWNER 夹具的真实数据库测试，未改当前网站设计。

另逐项验证暂存区内的 73 个技能文件与 SHA-256 清单一致，确认 Git 换行处理没有改变已审核字节。自有脚本/文档的差异空白检查通过；上游原有 46 处尾随空白按固定来源原样保留，未为了格式化改写第三方代码或数据。新增行数主要来自离线目录与来源元数据，不是网站业务代码变化。
