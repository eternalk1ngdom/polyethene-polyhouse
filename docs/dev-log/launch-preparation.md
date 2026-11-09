# 云端上线验收清单

当前架构与已完成事项见 [v1.0-dev.13](v1.0-dev.13.md)。本清单替代早期主机采购和本机穿透方案。

本地容器实施和分阶段放行见 [Render 后端生产适配计划](../plans/render-docker-production.md)。

- [x] GitHub Pages 已通过 Actions 构建前端。静态资源使用仓库路径，页面使用 Hash 路由。
- [ ] Render 部署 Spring Boot，验证监听 `PORT`、冷启动与应用内存；核对计划、用量上限和休眠规则。凭据经服务端环境变量注入。
- [ ] 验证 Render 入站代理覆盖可信来源标识，伪造转发头和直连无法绕过限流。按实测容量验收入口总预算、登录和敏感操作的独立限额，超限返回 429 并在窗口结束后恢复。复核现有 Tomcat 安全公告的代理触发条件与处置，再放行公网。
- [ ] 使用证书校验的 TLS 连接目标 TiDB Cloud。按[数据库初始化说明](../operations/database-bootstrap.md)预检 CHECK/外键，执行统一建表脚本，在隔离云库验证唯一屋主、登录、头像审批与记录追溯。保留已有云库，确认切换目标后再更新 Render 连接。
- [ ] 使用生产 profile 连接 Upstash Redis 的原生 TLS 端点。验证登录撤销、头像额度键与过期时间；检查每日用量与缓存故障反馈。
- [ ] 前端构建注入 `VITE_API_BASE_URL`，值为 Render HTTPS 地址加 `/api`。核对 GitHub Pages 的 Origin 能通过预检，非白名单来源被拒绝。
- [ ] 用隔离测试账号做线上快验：登录、选图压缩、提交、本人/屋主私有预览、批准/驳回、刷新头像、每日额度、退出及容器重启后头像仍可读。
- [ ] 核对访问范围、个人信息处理、备份与隔离恢复。按实际环境确认服务条款、费用边界与合规要求，再开放试用。

平台资料：[Render 持久化说明](https://render.com/docs/disks)、[TiDB Cloud TLS](https://docs.pingcap.com/tidbcloud/secure-connections-to-serverless-clusters/)、[Upstash 客户端](https://upstash.com/docs/redis/howto/connectclient)。
