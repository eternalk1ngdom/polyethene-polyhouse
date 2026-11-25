# 仓库维护待办：缺陷与重复实现

审查基线为 `1838c2a`。以下条目均待实施。优先处理可能提交错误内容、丢失批量结果或意外运行外部验收的路径。修复每项时分别保留回归用例。

## 缺陷

### 1. 头像选图失败后仍可提交旧草稿（优先，组件已复现）

- **触发与影响**：在编辑器已有静态图 A 时改选 GIF B。界面显示错误，保存按钮仍可用，点击后提交 A。用现有组件夹具执行真实的 `choose` 和 `save`，观察到旧位图仍在，模拟上传被调用一次。[头像编辑器](../../polyhouse-web/src/components/AvatarUploadDialog.vue) · [组件夹具](../../polyhouse-web/tests/user/avatar-editor.test.mjs)
- **待办**：选中新文件时先使旧草稿失效；取消系统文件选择器则保留草稿。区分「新文件无效」与「有效草稿上传失败」，后者仍应支持重试。沿用已有代次检查、位图释放和申请编号机制。
- **验收**：分别换成格式不支持、超体积和解码失败的文件，确认保存不可用且上传调用为零。重新选择有效图片后可提交；服务端暂时失败后仍能重试原申请。

### 2. 妙压单个文件读失败会中断整个批次（优先，组件已复现）

- **触发与影响**：三张图片中第一张编码成功，第二张读取失败。真实组件最终状态为 `done / running / pending`，整批已退出忙碌状态，ZIP 回调次数为零。失败来自读取本地文件，和 Worker 正常返回「无法压缩这张」走了不同分支。[批量执行](../../polyhouse-web/src/components/TargetKbPanel.vue) · [生命周期夹具](../../polyhouse-web/tests/target-kb/target-kb-lifecycle.test.mjs)
- **待办**：在单项边界处理读取失败，给该行标记失败并继续下一项；取消、换批或退出页面仍立即终止旧批次。保留打包失败的独立提示。
- **验收**：三张中第二张不可读时，最终显示两张成功、一张失败；ZIP 只含第一、第三张且顺序正确。全失败时无 ZIP，读取中取消后无旧结果回流。

### 3. 普通验收脚本未覆盖后来新增的专项开关（优先，配置差异已确认）

- **触发与影响**：普通验收入口显式关闭多项实例测试，但遗漏 `polyhouse.trend.integration`（6 个测试类），以及 `polyhouse.trend.redis`、`polyhouse.trend.http`、`polyhouse.trend.browser`、`polyhouse.trend.live`、`polyhouse.trend.live.acceptance`。环境向 Maven 测试进程传入对应开关且满足前置条件时，普通验收可能启动实例或外部调用。默认未设开关时不触发。另有方法级 `polyhouse.workspace.browser` 未显式列出，但它还受已关闭的 profile 类级开关保护。[普通验收入口](../../polyhouse/scripts/Test-Project.ps1) · [真实采集测试](../../polyhouse/src/test/java/com/polyethene/polyhouse/trend/GithubTrendLiveTest.java)
- **待办**：补齐普通入口传给 Maven 的关闭参数；加入轻量检查，在新增 JUnit 专项开关时提示同步维护。保留显式专项验收入口。
- **验收**：用命令捕获替身核对参数，在隔离环境确认专项测试跳过、普通测试执行。此次仅比对注解与脚本，未运行真实服务或触发费用。

### 4. 星潮历史分页失败后，「重试历史」丢失分页位置（中，组合函数已复现）

- **触发与影响**：第一页成功，点「更早日期」失败，再点错误提示旁的「重试历史」。用真实组合函数和替身接口记录请求游标，依次为无游标、上一页游标、无游标。第三次重新取首页；若已成功加载多页，还会替换累计的日期列表。[读取逻辑](../../polyhouse-web/src/features/trend/useTrendEdition.ts) · [按钮入口](../../polyhouse-web/src/views/TrendView.vue)
- **待办与验收**：保留上一次失败操作的方向和游标。首屏失败重试首页；第三页失败时保留前两页，重试相同游标并追加；遇到限流时遵守现有等待时间。

### 5. 本地卡面写入成功后，跨标签通知失败可能误报保存失败（低，待浏览器验证）

- **依据与条件**：写入函数先等待 IndexedDB 事务完成，再创建广播通道并发消息；外层把两步的异常都解释为保存失败。仅当存储可用、通知步骤单独抛错时才出现。浏览器完全缺少该 API 的情况已有检查。[存储与通知](../../polyhouse-web/src/utils/localProfileCover.ts) · [保存入口](../../polyhouse-web/src/components/ProfileCoverPicker.vue)
- **验收及修复方向**：先在隔离浏览器夹具中单独模拟通知失败，核对提示、当前卡面与刷新后的结果。若复现，以事务提交结果决定保存状态，保留其他标签页重新获得焦点时的刷新；真实存储写入失败仍应报错。

## 重复实现

| 待办 | 位置及影响 | 建议范围与验收 |
| --- | --- | --- |
| 6. 统一星潮仓库身份匹配（优先于其他重构） | [窗口统计](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/trend/TrendWindowAggregator.java)和[简报输入](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/trend/TrendBriefingInputBuilder.java)都建立名称到稳定 ID 的索引，并在缺 ID 时回查唯一候选。只改一处可能令榜单计数和简报样本归属不同。 | 在星潮模块内共用身份解析，保留各自的排序、统计和筛选。覆盖改名同 ID、同名不同 ID、缺 ID 但能唯一回查、无法回查四种情况；两端结果与既有样本一致。 |
| 7. 共享账号集成测试临时库夹具（中） | [账号管理](../../polyhouse/src/test/java/com/polyethene/polyhouse/user/acceptance/AccountManagementIntegrationTest.java)、[开户](../../polyhouse/src/test/java/com/polyethene/polyhouse/user/acceptance/AccountProvisioningIntegrationTest.java)、[账号安全](../../polyhouse/src/test/java/com/polyethene/polyhouse/user/acceptance/AccountSecurityIntegrationTest.java)重复构造连接、限定本机和管理随机库生命周期。 | 仅抽测试公共部分，分别保留夹具数据、迁移版本和业务断言。在隔离环境验证三个套件各自创建和清理独立库，异常退出也不能误清开发库。 |
| 8. 共享妙压前端测试模块加载器（低） | [压缩计划测试](../../polyhouse-web/tests/target-kb/target-kb-plan.test.mjs)与[目标体积测试](../../polyhouse-web/tests/target-kb/target-kb-target.test.mjs)等重复内存编译 TS 和替换模块地址。依赖替换需求随用例不同。 | 先合并等价加载函数，保留不同依赖替身。对照改动前后发现的测试名称、数量与断言结果；辅助文件不进入生产包。 |
| 9. 星潮面板基础样式（低，随界面改动处理） | [简报控件](../../polyhouse-web/src/features/trend/TrendBriefingControl.vue)、[费用账本](../../polyhouse-web/src/features/trend/TrendFeeLedger.vue)、[任务进度](../../polyhouse-web/src/features/trend/TrendTaskProgress.vue)重复卡片边框、内边距及标题行布局。 | 仅在下次修改这些面板时共用稳定的样式，保留告警、费用和任务专属状态。桌面及窄屏对照标题、按钮换行和焦点；不因样式相同而合并业务组件。 |

## 架构与性能复查

以下条目基于 `f95ed34`，沿用项目当前依赖。顺序按改动收益与验证成本排列。下面的次数来自指定样例或正常成功调用链，不能直接推算线上响应时间。

### 10. 个人页在资料未变时重复刷新卡面目录（优先）

- **调用链**：[卡面选择器](../../polyhouse-web/src/components/ProfileCoverPicker.vue)用一个每次返回新数组的 getter 观察角色、两个等级和奖励日期。用仓库当前 Vue 执行原监听表达式，初始化后分别用字段相同的新对象替换资料及成长摘要，中间各等待一次更新，共触发 3 次。页面回焦、打开弹窗也会主动读取；`sequence` 仅丢弃旧响应。一次成功目录请求会锁定用户行、校验会话、读取购买及佩戴记录，必要时纠正失效偏好。[服务端目录](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/ProfileCoverService.java)
- **最小改法**：先改为分别观察各个标量的 Vue 监听源。再核对[成长刷新](../../polyhouse-web/src/stores/useUserGrowthStore.ts)临时清空摘要导致的“有值→空→原值”，避免把加载中当成资格变更。只复用同账号、同一刷新原因下仍有效的在途读取。购买、佩戴后的权威重读、换号清理、跨日和回焦校验继续生效。
- **验收**：用接口计数覆盖初次进入、同值资料刷新、回焦时打开弹窗、跨日和真实升级。同值替换不新增目录 GET；弹窗正确选中当前款。购买期间的旧目录不能覆盖新余额或权益。保留服务端锁序和失效偏好纠正语义。

### 11. 妙替密集删除时为零长度结果累积数组条目（优先）

- **证据**：[替换内核](../../polyhouse-web/src/core/acReplacer.ts)每次命中都追加未匹配前缀和替换文本。用 Node 在内存中给原函数的追加点计数，65,536 个连续 `a`、规则为删除 `a`，得到 65,536 次命中、0 长度结果，命中分支却写入 131,072 个空数组条目。这里统计的是数组元素数量，不是独立字符串分配数。
- **最小改法与验收**：仅把非空片段加入数组，保留匹配计数、按规则统计、删除标记和预览坐标。以无匹配、稀疏替换、连续删除、删除夹杂保留文本四类输入对照结果和预览，用现有基准分别记录耗时和内存。此项无需改匹配算法。

### 12. 星潮同一窗口在一次浏览中读取并计算两遍（后端优先测量）

- **调用链**：[阅读页](../../polyhouse-web/src/features/trend/useTrendEdition.ts)进入七日视图会并行读取窗口统计和简报。两个服务分别调用 `windowSnapshot`，各查一次锚点头、一次日期区间，解码同一批日报，再分别调用聚合器。正常成功且窗口七天齐全时，仅快照部分就有 4 次数据库查询、14 次日报 JSON 解码和 2 次聚合，另有简报自身的存储读取。简报缺席时当前接口仍需返回生成资格等字段。[窗口接口](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/TrendReportService.java) · [简报接口](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/TrendBriefingService.java)
- **建议**：先用七天固定夹具测量查询次数、归档大小、解码和聚合耗时。若值得做，让阅读页同一次加载共用一份快照与统计；保留两个既有接口供原调用方使用。若用跨请求缓存，键需包含窗口内所有已发布版本，并限制容量；仅用锚点日期会漏掉历史日报重发。优先试验请求内复用，避免先引入跨实例缓存一致性问题。
- **验收**：阅读列表、证据链接、生成资格及过期提示与原结果一致；旧日期重发能及时体现；简报缺席或处理失败时统计仍可读。对照单人首次访问与并发重复访问的耗时和数据库调用，再决定投入。

### 13. 文件导出边界存在整份结果复制（中）

- **证据**：[妙替 Worker](../../polyhouse-web/src/workers/polyReplace.worker.ts)把已生成的 Uint8Array 再构造成新 Uint8Array 后才转移所有权；单文件结果也走此路径。[妙压批量打包](../../polyhouse-web/src/components/TargetKbPanel.vue)在构造 ZIP Blob 前先调用 `archive.slice()`，显式复制整个归档。结果越大，临时字节数越多。现有[妙压 Worker](../../polyhouse-web/src/workers/targetkb.worker.ts)已有“完整独占缓冲区直接转移，子视图才复制”的局部做法。
- **建议及验收**：分别核对 Worker 所有权、视图偏移及长度、原缓冲区后续用途；ZIP 路径核对现有 TypeScript 对 Blob 输入的类型约束。逐个消除可证实的复制。验证导出长度、逐字节内容、子视图前后多余字节不会进入文件，以及取消重跑、ZIP 解压均正常。用相同的大文件在同一浏览器对比峰值；引擎内部 Blob 的复制行为不计作已消除的开销。

### 14. 简报候选材料按条反复序列化整个前缀（低，接第 12 项观察）

- **证据**：[材料构造器](../../polyhouse/src/main/java/com/polyethene/polyhouse/service/trend/TrendBriefingInputBuilder.java)每试加一个候选，就把基础摘要和当前全部候选序列化，再对结果做 JSON 字符串转义以计算 UTF-8 字节数。若 45 个候选全部容纳，仅候选对象在这些前缀中就累计出现 1,035 次。这段逻辑也会被简报读取调用。45 条和 16 KiB 的现有上限限制了实际规模。
- **决策条件**：先测第 12 项共享窗口后的材料构造占比。仍明显时，再试验按候选累计准确的转义字节开销、最后统一生成材料。用中文、引号、反斜杠、空数组和恰好越过 16 KiB 的样例比较最终材料、候选顺序、指纹及复用判断，要求逐字节一致。若测不到可辨别收益，保留现状。

## 审查边界

复查了头像和个人卡面、妙压批量、妙替文件与导出、星潮历史分页与费用查询前后端、窗口统计及简报身份逻辑、集成测试与普通验收入口。组件样例使用替身文件、网络或 Worker。未执行生产数据操作、付费请求、全量真实浏览器和真机流程。本清单不构成对其余路径无缺陷的保证。
