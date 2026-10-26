# Polyhouse（聚妙屋）工程约定

## 执行入口
- 本文件是仓库唯一的长期工程约束。任何修改开始前都必须完整阅读，并在整个任务中持续遵守。
- 涉及目录设计、接口设计或业务代码时，必须同时完整阅读根目录 `Polyhouse_Ultimate_Master_Blueprint.docx`。
- `Polyhouse_Ultimate_Master_Blueprint.docx` 是只读策划书：允许引用，不得修改、重命名或转换后覆盖。
- `SKILL.md` 只用于独立、可复用且需要按需触发的工作流；不得复制本文件形成第二份工程约束。

## 项目结构
- `polyhouse/`：后端，Spring Boot 3 + JDK 21 + MyBatis，根包固定为 `com.polyethene.polyhouse`
- `polyhouse-web/`：前端，Vue 3 + TypeScript + Vite + Element Plus
- `Polyhouse_Ultimate_Master_Blueprint.docx`：设计文档，**只读**，不要修改
- `DEV_LOG.md`：开发日志，只在明确要求时追加，保持既有格式

## 铁律（违反即返工，不许"下次注意"）

1. **不改配置**：`pom.xml` 的依赖版本、`vite.config.ts` 的 proxy/alias/插件一律不动，只能追加。需要升级先问我。
2. **不省略代码**：输出完整文件，禁止 `// 其余代码不变`、`// ...`、`// TODO` 之类占位。没改的文件明确说"未改动"。
3. **三层隔离**：Controller 入参只用 `model.dto.*` 且带 Jakarta Validation 注解（`@NotNull/@NotBlank/@Size/@Positive` 等，方法参数加 `@Valid`）；出参只用 `model.vo.*` 并做脱敏；统一返回 `Result<T>`；**禁止直接接收或返回 Entity**。
4. **权限**：角色只用 `UserRoleEnum`（USER=10 / INSIDER=50 / OWNER=99），禁止用数字字面量比较。有服务器开销或调用 AI/第三方收费接口的方法**必须**加 `@RequireRole(UserRoleEnum.INSIDER)`。
5. **工具类纯净**：`utils/` 只放无状态静态纯函数，禁止注入 Bean、禁止调用 Mapper/Redis/HTTP。
6. **前端类型**：只用 `<script setup lang="ts">`；props、emits、接口响应必须声明 `interface`；禁止 `any`，禁止 `@ts-ignore`（必要时用 `unknown` + 类型收窄并写明原因）。
7. **算法内核红线**：`polyhouse-web/src/core/` 和 `src/workers/` 是纯 TS 算法内核，禁止引用 `window/document/navigator`，禁止引入 Element Plus 或任何 UI 组件，禁止算法内部依赖 Vue 响应式。算法与 UI 只通过参数和返回值通信。
8. **UI 规范**：统一 Element Plus + `@element-plus/icons-vue`；样式用 scoped；滑块、拖拽、画布交互必须用 Pointer Events（`pointerdown/move/up` + `setPointerCapture`）。
9. **注释必须写**：每个 public 类/接口/枚举、每个 public 方法、DTO/VO 的每个字段、枚举每项、以及所有非直观逻辑（正则、位运算、状态机、SQL 优化、并发/缓存策略）都要注释，说明"为什么这么做"。禁止 `i++; // i 自增` 这种复读机注释，禁止留注释掉的死代码，改代码必须同步改注释。
10. **安全与日志**：禁止硬编码密码/密钥/token；禁止空 `catch`；日志用 SLF4J `{}` 占位符，禁止 `System.out`，禁止打印密码、token、手机号；SQL 只用 `#{}`，禁止 `${}` 拼接用户输入。

## 完成前必须实际执行（不许跳过、不许谎报）
- 后端：`cd polyhouse; mvn -q compile -DskipTests`
- 前端：`cd polyhouse-web; npm run build`

有报错就原地修，反复"构建 → 修 → 再构建"，直到零编译错误、零类型错误。

## 汇报格式（固定四段）
1. 改动文件清单（相对路径 + 一句话说明）
2. 关键决策与风险
3. 实际执行的命令 + 结果
4. 未完成项与建议

## 改动后的讲解要求
- 每轮修改或新增文件后，必须在当轮汇报中主动讲解，不等用户再次追问。覆盖代码、配置、脚本、测试和文档；逐个文件或按职责相同的文件组说明改了什么、为什么要改，以及改动后实际产生的行为或作用。
- 涉及代码时，必须解释核心实现原理，包括关键类、方法或字段的职责，以及必要的调用关系、数据流或执行步骤，让用户能理解代码如何工作，不能只罗列文件名、技术名词或复述 diff。
- 使用通俗中文，首次出现的关键术语应结合本项目解释；按改动复杂度安排讲解深度，必要时给出简短示例，避免机械逐行翻译。明确区分已经实现的能力、仅有的骨架或预留，以及尚未验证的部分。
- 讲解并入既有固定四段汇报：第一段说明文件及具体变化，第二段说明原理、设计原因和风险，第三段列实际验证及结果，第四段说明未完成边界与建议；不改变原有汇报结构。

## 其它
- 不擅自 `git commit` / `git push`，除非我明确要求。
- 不做任务范围外的"顺手优化"，不重排无关代码。
- 不确定的类名、方法名、字段名，先搜索确认，禁止臆造 API。
