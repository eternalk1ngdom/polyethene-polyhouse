# DeepSeek 密钥配置与 G2 验证边界

用户已选择官方平台。固定使用 `https://api.deepseek.com/chat/completions`，不需要提供额外接口地址；不接受 Prompt、前端或配置把 Key 重定向到其他域名。官方协议与申请入口见[DeepSeek 文档](https://api-docs.deepseek.com/)。

## 当前无需提交密钥

G2 实现及数据库测试只用假 Key、模拟模型。真实模型质量、响应和费用尚未验证，不将模拟成功当作真实接入成功。当前生成仍是内部能力，尚无 G3 屋主触发入口；可以先准备专用 Key，等受控链路就绪后再进行一次完整真实联调。

不要把 Key、包含 Key 的截图、请求头或环境变量输出发到聊天中。助手不需要查看明文；只需在后续联调时回复“已在本机配置，启动成功/失败”，失败只提供脱敏状态或固定错误。

## 可选本机启动包装

脚本 [Invoke-TrendAiLocal.ps1](../../polyhouse/scripts/Invoke-TrendAiLocal.ps1) 不替代原启动入口，不改写 IDEA 配置，不保存 Key 文件。`Inspect` 只显示三个布尔状态，不访问 API，也不证明 Key 有效：

```powershell
./polyhouse/scripts/Invoke-TrendAiLocal.ps1 -Action Inspect
```

后续完成真实目标库的 010/011 迁移、G3 受控入口和真实联调安排后，在自己的 PowerShell 终端、仓库根目录运行：

```powershell
./polyhouse/scripts/Invoke-TrendAiLocal.ps1 -Action Run -EnableAi
```

出现隐藏输入提示时再输入聚妙屋专用 Key，正常终端仅显示掩码。命令本身不含 Key，因此不会把真实值写入 PowerShell 命令历史；脚本没有持久化逻辑。若启用了剪贴板历史/云同步，复制粘贴仍可能留下副本，避免把它贴到其他窗口或截图中。

包装只向本次启动进程树注入 `POLYHOUSE_DEEPSEEK_API_KEY`；`-EnableAi` 显式启用模块及 AI，**不自动迁移或触发付费请求**。随后复用已有 `Invoke-LocalAuth.ps1 -Action Run`；正常或异常结束均恢复原环境。原来已有进程变量时保留原值，不清除用户原配置。需要其他 Redis 环境时沿用 `-SkipWslRedis`。

隐藏输入与环境继承机制见 [Read-Host](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/read-host?view=powershell-7.5) 和[进程环境变量](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_environment_variables?view=powershell-7.5)。运行时仍需明文供 HTTP 认证使用，同权限恶意程序、内存转储或不当调试可能读取；不承诺绝对不泄露。不要打印环境、开启包含认证头的 HTTP 调试或分享运行转储。

## 降低盗用损失

- 为聚妙屋单独创建 Key，不与其他程序共用；只在自己的后端和官方控制台使用。
- 本模块不提供浏览器读取 Key 的接口，也不用 `VITE_` 变量、仓库文件或 `.env` 保存真实值。Git 忽略不是加密措施。
- 先保持少量账户余额并查看用量。应用内十元预算只约束本模块，不能限制窃取者绕开本站直接调用。
- 一旦怀疑泄露，在官方控制台立即撤销旧 Key，换新并更新本机进程；不要只从聊天或文件中删除旧文本。
- 云端后续改用平台服务端 secret 环境变量；不把本机 Key 写入镜像或 GitHub Pages。云端变更另行授权。

## G2 计费和未完成项

当前初选 `deepseek-flash`，非思考、非流式、JSON 输出、最大输出1500 token，不配置工具。按官方人民币高峰、缓存未命中的输入2元/百万token、输出8元/百万token保守估算（2026-11-16核对）；真实联调前还需确认账户币种及届时价格，见[官方价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)。预算账本是上界估算，不等同平台实际账单。

完整请求最多32KiB，字节数加512作为输入保守上界。调用前持久预留，月用额加预留不得超过10元；同一执行/仓库确定请求UUID，未完成或结果不明不自动重发。已返回可信用量但内容非法仍结算，无用量/超时保留预留及仓库槽，用量超上界暂停该月。未知记录不会自动退额或按过期删除；核对与受控处理留给G3，不手工删账本绕开预算。

追加 [011迁移](../../polyhouse/sql/011_trend_ai.sql) 建立月账本、仓库缓存/槽、请求事实三表，不保存Key、Prompt或原始响应。按[模块运维](github-tech-radar.md)先确认目标和备份；回退先关闭AI并保留账本，必要时按请求表、仓库表、月份表的顺序移除，仅操作已确认目标。G2未对开发或云端库执行迁移。

离线测试命令、结果和剩余真实验证见[阶段日志](../dev-log/v1.0-dev.15.md)。安全包装可用 `./polyhouse/scripts/tests/trend/Test-TrendAiLocal.ps1` 验证，它只使用假Key与假后端，不启动Java或付费。
