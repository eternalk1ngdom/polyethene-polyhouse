# 数据库初始化与回退

本机 MySQL 8.0 和 TiDB Cloud MySQL Compatible 共用 [001_schema.sql](../../polyhouse/sql/001_schema.sql)。先创建并明确选中一个空库，再执行脚本。脚本建立 13 张业务表、一个空屋主槽和起始发号记录。首次执行后用户表为空，应用启动时也不会自动建表或开户。

## 建库与核对

1. 本机停写并备份后，在 MySQL 客户端执行 `CREATE DATABASE polyhouse_db CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;`。使用 `USE polyhouse_db;` 选中目标。TiDB Cloud 使用控制台给出的 TLS 参数连接并明确选中目标空库。
2. TiDB 执行前查看 `SHOW GLOBAL VARIABLES WHERE Variable_name IN ('tidb_enable_check_constraint','tidb_enable_foreign_key');` 和 `SELECT @@foreign_key_checks;`。要求三项均启用。TiDB 的 CHECK 默认关闭，由有权限的运维人员在目标实例按需启用。若实例无法启用，暂停导入并核对该实例的限制。[TiDB CHECK 说明](https://docs.pingcap.com/tidbcloud/constraints/) · [TiDB 外键说明](https://docs.pingcap.com/tidbcloud/foreign-key/)
3. 确认 `SELECT DATABASE();` 返回预期库且 `SHOW TABLES;` 为空。用 MySQL 客户端的 `source` 执行 SQL 文件，或在 TiDB SQL Editor 执行同一文件。失败后核对目标库并修复原因，重新使用空库。DDL 可能分次提交。
4. 检查 13 张表、`sys_owner_bootstrap.slot=1` 的空记录及 `sys_account_sequence.next_value=1`。用 `SHOW CREATE TABLE sys_user;` 核对头像字段、约束和唯一屋主索引，用 `SHOW CREATE TABLE sys_user_growth_reward;` 核对奖励流水外键。云端先在隔离库实测约束和应用连通，再切换后端连接。

## 已有库允许创建内部用户

发布支持内部用户开户的后端前，先备份已有目标库。用 `SELECT DATABASE();` 确认当前库，用 `SHOW CREATE TABLE sys_account_creation;` 核对约束。TiDB 还需确认 `SHOW GLOBAL VARIABLES LIKE 'tidb_enable_check_constraint';` 返回 `ON`。

- 旧库只有 `chk_account_creation_role` 时，从 [001_schema.sql](../../polyhouse/sql/001_schema.sql) 文末的 `EXISTING_DATABASE_INSIDER_MIGRATION` 注释块复制两条 `ALTER TABLE`，按顺序逐条执行。先添加新约束，再移除旧约束。已有库不要重跑整份文件。
- 如果新旧约束同时存在，确认新约束允许 `USER`、`ADMIN` 和 `INSIDER` 后只执行第二条。如果已有正确的新约束且无旧约束，已完成，无需执行。其他结构或报错先核对再继续。
- 完成后再次执行 `SHOW CREATE TABLE sys_account_creation;`，确认开户角色只受新版 `chk_account_creation_role_v2` 约束，且新版约束处于启用状态。
- 需要恢复旧约束时先停写，确认 `SELECT COUNT(*) FROM sys_account_creation WHERE role = 'INSIDER';` 为零，再添加 `chk_account_creation_role`（只允许 `USER`、`ADMIN`），随后删除 `chk_account_creation_role_v2`。如果已有内部用户开户记录，保留新版约束和历史数据。

## 创建首个屋主

通过私有运行环境提供目标库的 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`。云端 JDBC 使用 TiDB 提供的证书验证配置。以下命令从 `polyhouse/` 运行，示例账号 `root` 是网站账号，和数据库连接账号独立。

```powershell
mvn -q compile dependency:build-classpath '-Dmdep.outputFile=target/owner-classpath.txt' '-DincludeScope=runtime'
$ownerClassPath = 'target/classes;' + (Get-Content -LiteralPath 'target/owner-classpath.txt' -Raw).Trim()
java -cp $ownerClassPath com.polyethene.polyhouse.ops.OwnerBootstrapCommand --create --username=root --apply
```

在交互终端依次响应 `Owner password:` 和 `Confirm owner password:`，两次输入同一个密码，输入内容不回显。空密码或两次不一致会在连接数据库前退出。命令用应用的 BCrypt 随机盐哈希，在同一事务内创建用户并写入首次初始化记录。若已有任意用户或首次初始化记录，命令拒绝创建。已有库需要核对或授予已有账号时，见[已有账号的屋主初始化](owner-bootstrap.md)。

## 备份与回退

- 停写后用 `mysqldump --single-transaction --no-tablespaces --set-gtid-purged=OFF --result-file=<备份文件> polyhouse_db` 备份本机旧库，将文件放在 Git 忽略且受访问权限保护的目录。备份含密码哈希、头像等用户数据。
- 先恢复到独立空库，核对表清单和逐表行数，再重建。若需回退，停写并保留新库快照，把旧备份恢复到独立空库后再切换连接，或者经确认重建原库名并恢复。数据库和应用版本应一起回退到匹配的基线。
- 合并前 001～009 迁移保留在 Git 提交 `50e8b9b`，用于历史库核对。已有库的增量升级应依据实际版本和结构单独安排。
