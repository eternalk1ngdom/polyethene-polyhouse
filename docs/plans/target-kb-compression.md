# 妙压：图片体积卡位（TargetKB）

制定日期：2026-11-01（Asia/Shanghai）。状态：**T1～T3 已实施并通过验收**；T4～T5 为后续候选，未获批准。

所属阶段：v1.0 本地开发期，当前日志 [v1.0-dev.11](../dev-log/v1.0-dev.11.md)。本计划在独立工作树 `E:/project/java/polyhouse-press`、分支 `wb/targetkb` 上推进，与妙替深化（`codex/replace-library`）并行；工程约束仍以根 [AGENTS.md](../../AGENTS.md) 为准，并行开工不扩大推送、合并或发布授权。

## 0. 本轮批准范围与验收边界

2026-11-01：用户确认双线开工方案，并要求先出计划、首轮只做算法内核与测试。因此 T1 范围固定为：

- 只新增或实现**纯函数模块与专项测试**；不接 Worker、不建消息协议、不改页面、不改 `toolRegistry` 状态。
- 复用预留空壳 `src/core/jpegComInjector.ts`（当前仅 `export {}`），新增 `src/core/targetKbCompress.ts`、`src/core/targetKbTypes.ts`。
- 不新增任何依赖（`fflate`、`pdf-lib` 已存在，T1 用不到）；不改 `vite.config.ts`、`package.json`。
- 不修改妙替的任何文件；不推送、不合并、不部署。

T1 验收方式：`node --test` 专项、`npm run lint:check`、`npm run build`（含 `vue-tsc`）。无后端改动不运行 Maven。**T1 不产生任何用户可见变化**，页面仍是占位页，汇报必须如实说明，不能用"内核完成"暗示功能可用。

2026-11-01 实施结果：T1 已按第 8 节建议值完成，专项 23 项、全量前端 158 项测试与构建通过；实际证据见 [dev.11](../dev-log/v1.0-dev.11.md)。

2026-11-01（T2）：用户确认 T2 范围，并追加四条约束——协议加递增 id 且导出 `isCurrentReply`、`targetKbSource.ts` 复用 `avatarSourceSize()` 不重写段解析、真实样本放 gitignored 目录且至少三类、临时验收页必须放在 `polyhouse-web/` 根目录且跑完删除。T2 只做 Worker 与协议，仍不改页面、`toolRegistry`、`vite.config.ts`、`package.json` 与妙替文件。

2026-11-01（T3）：用户确认按计划继续，T3 范围固定为 `views/TargetKbView.vue` 与 `components/TargetKbPanel.vue`，并在完成后把 `toolRegistry` 的 `target-kb` 改为 `available`；验收要求真实图片端到端、布局与焦点按铁律 8 实际核对，不能只用构建通过代替。

### 0.1 双线协作约定

- 工作树：妙替在 `E:/project/java/polyethene-polyhouse`（分支 `codex/replace-library`）；妙压在 `E:/project/java/polyhouse-press`（分支 `wb/targetkb`，基线 `efda7b6`）。两者共享同一个 `.git`，同一分支不能在两处同时检出。
- 基线选 `efda7b6`（只含 AGENTS/DEV_LOG/dev.9 的规范提交）：既拿到最新契约，又不把妙替代码带进妙压分支。
- 本分支不含 dev.10（妙替 R1），DEV_LOG 中已注明来源分支；合并顺序建议**妙替 R2 → 妙压**，阶段编号才连续。
- 共享文件只有三处：`DEV_LOG.md`、`docs/plans/README.md`、`src/config/toolRegistry.ts`。妙压侧只动 `target-kb` 条目与自己的计划/日志行；`toolRegistry.ts` 在 T1 不改。

## 1. 判断与范围

现状（2026-11-01 核对）：

- `views/TargetKbView.vue` 共 12 行，只渲染 `ToolComingSoon`；`config/toolRegistry.ts` 中 `target-kb` 的 `status` 为 `planned`。
- `core/jpegComInjector.ts` 与 `workers/targetkb.worker.ts` 都是 `export {}` 空壳，没有任何可调用实现。
- 依赖中已有 `fflate`（ZIP）与 `pdf-lib`；`core/avatarCrop.ts` 的裁剪思路可参考，但妙压不做裁剪交互。
- 策划书 Phase 3 只给出方向（体积卡位、证件照/PDF、云预设），审查报告曾暂停妙压；本计划是恢复实施的第一步，不含上线。

**为什么先做内核**：体积卡位的真正难点是"在有限次数内逼近目标体积"，这是纯逻辑，可以在 Node 里完整测试；而真正的编码（`OffscreenCanvas`）只能在浏览器验证。两类问题混在一起做，失败时无法判断是搜索逻辑错还是编码参数错，这是妙替 R6 当初被后置的同一个理由。

## 2. 基线与目标

| 现状 | 位置 | 目标（T1 已完成部分见 dev.11） |
| --- | --- | --- |
| 妙压只有占位页 | `views/TargetKbView.vue:1-12` | 已完成：T3 换成真实页面 |
| 压缩字节层为空壳 | `core/jpegComInjector.ts:1` | 已实现 COM 增重与 DPI 改写 |
| 无目标体积解析与判定 | — | T1 实现 |
| 无搜索策略 | — | T1 实现二分质量 + 尺寸降档 |
| Worker 为空壳 | `workers/targetkb.worker.ts:1` | 已完成：T2 接入 `OffscreenCanvas` 编码与协议 |
| 工具状态为 planned | `config/toolRegistry.ts:43-54` | 已完成：T3 完成后改为 `available` |

## 3. 算法设计

### 3.1 目标体积与判定

- 上限 `maxBytes` 必填，下限 `minBytes` 可选（部分系统要求"不小于 30KB"）。
- 判定三态：`within`、`too-large`、`too-small`；只给上限时区间为 `[1, maxBytes]`。
- 解析接受 `200KB`、`1.5MB`、`307200` 这类写法，统一换算为字节；拒绝空值、负数、非数字、以及超过 20 MiB 的目标值。

### 3.2 二分质量搜索

纯函数只决定"下一步试什么"，编码由调用方注入，因此可在 Node 里用假编码器验证：

```
startSearch(input) -> { kind: 'probe', probe, state }
advanceSearch(state, bytes) -> { kind: 'probe' | 'done' | 'failed', ... }
```

- 先以质量 1.0 试一次：原图本来就不超限时一次结束，不必牺牲画质。
- 之后在 `[0.10, 1.00]` 内二分，单档位最多 12 次评估，收敛阈值 0.001。
- 只以"不超过上限"为约束求最大质量；**低于下限不再继续抬质量**，交给 COM 段补齐。区间搜索在"最高质量仍小于下限"时会无解，补齐才是正确出口。
- 12 次后取"满足上限的最大质量"；一个都没有则降档或失败，**不输出超限文件**。

### 3.3 尺寸降档

- 最低质量仍超上限时，按 `0.85` 连乘缩最长边，最多 6 档，最小边长 64px。
- 每档重新二分；6 档后仍失败，返回明确失败原因（建议提高压缩比或缩小尺寸），不静默交付一个超限结果。

### 3.4 小图增重（JPEG COM 段）

- 输出小于下限时，插入 COM（`0xFFFE`）段填充；单段数据上限 65533 字节，超出拆多段。
- 补齐在搜索结束后按 `padToBytes` 执行，不改像素、不改编码；差额小于 5 字节时无法精确补齐，返回未满足而不是写出超限文件。
- 插在头部元数据（APPn 与 COM）之后，保证 JFIF 仍是最前面的段；必须验证遍历段后仍能正确找到 EOI。
- 局限：只对 JPEG 有效。T1 输出统一 JPEG，所以不影响；将来若支持 PNG 输出需另设方案。

### 3.5 DPI 写入

- 只改 JFIF APP0 的 `units=1` 与 `Xdensity`/`Ydensity`；没有 APP0 时插入一个。
- 不伪造 EXIF，不改变像素尺寸。
- 界面文案须说明：DPI 只是元数据，实际打印尺寸由像素数与 DPI 共同决定，单改 DPI 不会让图片变清晰。

### 3.6 纯函数边界

- 不读文件、不建 `Image`/`Canvas`、不访问存储、不引用 Vue 或 Element Plus，遵守铁律 7。
- 输入输出只用 `Uint8Array`、数值与类型对象；编码器通过参数注入。

## 4. Worker 协议（T2 已实现）

- 请求 `{ id, kind: 'encode' | 'cancel', bytes, target, allowShrink, dpi }`；回复 `{ id, ok, result | reason, message }`。
- 一条 `encode` 就是一次完整任务：Worker 内部跑完二分、降档、DPI 与补齐，只在结束时回复一次；`result.probes` 带每次试探的质量、字节与耗时。
- `id` 由页面递增分配，Worker 维护 `activeId`；`isCurrentReply(replyId, activeId)` 判断归属，`activeId = 0` 表示没有有效任务。取消只清 `activeId`，不终止线程。
- 页面层（T3）只按 id 判断回调归属，不再叠加 revision；妙替的 `polyReplaceProtocol.ts` 与 worker 保持原样，不统一风格。
- 落点：`core/targetKbProtocol.ts`、`core/targetKbSource.ts`（复用 `avatarCrop.ts` 的 `avatarSourceSize()`）、`workers/targetkb.worker.ts`。

## 5. 分步实施

| 步骤 | 工作 | 落点 | 独立验收 |
| --- | --- | --- | --- |
| T1 内核 | 目标解析与判定、二分与降档计划、COM 增重、DPI 改写 + 测试 | `core/targetKbTypes.ts`、`core/targetKbCompress.ts`、`core/jpegComInjector.ts` | 已完成：专项 23 项、全量 158 项、构建与静态检查通过；页面无变化 |
| T2 编码接入 | `OffscreenCanvas` 编码、Worker 协议、取消 | `workers/targetkb.worker.ts`、`core/targetKbProtocol.ts`、`core/targetKbSource.ts` | 已完成：真实 Chrome 三类样本通过；过期与取消 0 条迟到回复；证据见 dev.11 |
| T3 页面 | 选图与拖拽、参数、体积对比、单图下载 | `views/TargetKbView.vue`、`components/TargetKbPanel.vue` | 已完成：真实图片端到端、390px 无溢出、Tab 焦点逐项核对；证据见 dev.11 |
| T4 批量与导出 | 多图、ZIP、失败清单 | 页面 + `fflate` | 批量上限明确；失败不返回半成品 |
| T5 候选 | 证件照换底、PDF 压缩、云预设 | 另议 | 需单独批准 |

顺序理由：先"算得对"（T1），再"编得出"（T2），再"点得到"（T3），最后才是批量。

## 6. T1 验收清单

- [x] 目标解析：`200KB`、`1.5MB`、纯数字、空、负数、非数字、超 20 MiB、下限大于上限
- [x] 判定：恰好等于上限、超 1 字节、区间内、只给上限
- [x] 二分：单调编码器、非单调编码器、始终超限、始终小于下限、12 次内收敛
- [x] 降档：0.85 连乘、最小边长 64px、6 档后返回失败
- [x] COM：单段、多段、恰好 65533 字节边界、已有 COM 段不破坏、段遍历仍能定位 EOI
- [x] DPI：无 APP0 时插入、有 APP0 时改写、非法值拒绝、字节长度变化符合预期
- [x] `npm run lint:check`、`npm run build` 通过；无 `any`、无 `@ts-ignore`
- [x] 未改妙替文件、未改 `toolRegistry`、未新增依赖

专项 23 项通过；全量前端 158 项通过（基线 135 项 + 新增 23 项）。测试用假编码器与合成 JPEG 段，**没有验证真实浏览器编码结果**，那属于 T2。

## 7. 当前仍不做什么

- 不做 PNG/WebP 输出、不做批量、不做 ZIP、不做 PDF、不做证件照换底、不做云预设（T4/T5）。
- 不改 `vite.config.ts`、`package.json`，不加依赖。
- 不推送、不合并、不部署。
- 不改妙替的任何文件；页面层只按消息 id 判断回调归属，不叠加 revision。

## 8. 决定状态

| 编号 | 事项 | 建议 | 状态 |
| --- | --- | --- | --- |
| D1 | 首轮范围 | 只做纯内核 + 测试 | 已定（2026-11-01） |
| D2 | 输出格式 | T1 统一 JPEG | 已定（2026-11-01 按建议值实施） |
| D3 | 小图增重方式 | JPEG COM 段填充 | 已定（同上） |
| D4 | DPI 写法 | 只写 JFIF APP0 | 已定（同上） |
| D5 | 输入上限 | 单张 ≤ 20 MiB、≤ 40 MP | 已实现（2026-11-01）：解码前校验，WebP/AVIF 解析不出尺寸时退回解码后补检 |
| D6 | 搜索参数 | 质量 0.10–1.00、12 次、降档 0.85×6 | 已定（同上） |

## 9. 参考

- 代码：`polyhouse-web/src/core/jpegComInjector.ts`、`workers/targetkb.worker.ts`、`views/TargetKbView.vue`、`config/toolRegistry.ts`；可对齐的妙替实现：`core/acReplacer.ts`、`workers/polyReplace.worker.ts`、`core/polyReplaceProtocol.ts`。
- 文档：[只读策划书索引](../design/blueprint-index.md) Phase 3 行、[审查报告](../operations/project-review-2026-10-28.md) 第 5 项、[前端方案二](../design/frontend-v2.md)。
