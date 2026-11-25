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

## 审查边界

复查了头像和个人卡面、妙压批量、妙替文件与导出、星潮历史分页与费用查询前后端、窗口统计及简报身份逻辑、集成测试与普通验收入口。组件样例使用替身文件、网络或 Worker。未执行生产数据操作、付费请求、全量真实浏览器和真机流程。本清单不构成对其余路径无缺陷的保证。
