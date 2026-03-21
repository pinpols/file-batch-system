# Quartz PostgreSQL 接入方案

## 版本口径

- Spring Boot 4.0.3 当前解析到的 Quartz 版本：`2.5.2`
- 确认方式：本地执行 `mvn -q help:evaluate -Dexpression=quartz.version -DforceStdout`

## 官方脚本来源

Quartz 官方 PostgreSQL 建表脚本位于：

- Quartz Wiki 指向的 JDBC JobStore SQL 目录
- Quartz 发行包 / JAR 内资源路径：`org/quartz/impl/jdbcjobstore/tables_postgres.sql`

本目录中的脚本基于 Quartz `2.5.2` 官方 `tables_postgres.sql` 做了以下接入性处理：

- 所有表显式落到 `quartz` schema
- `CREATE TABLE` / `CREATE INDEX` 增加 `IF NOT EXISTS`
- 去掉官方脚本中的 `DROP TABLE IF EXISTS`
- 保留官方表结构、主键、外键和索引口径

## 边界说明

- `quartz` schema 只允许存放 `QRTZ_*` 元数据表
- 业务触发请求、实例状态、补偿、审计等表必须继续放在 `batch` schema
- `quartz.QRTZ_*` 与 `batch.*` 之间不建立业务外键

## 推荐接入方式

### 方式 A：独立初始化 Quartz 表

适用于：

- 希望 Quartz 元数据和平台业务表分离治理
- 不希望 Quartz 表和平台业务 Flyway 版本号混在一套迁移链中

执行脚本：

- `docs/sql/quartz/Q1__create_quartz_tables_postgres_2_5_2.sql`

### 方式 B：独立 Flyway Location

建议：

- 平台业务表继续走 `batch` schema 的主 Flyway 路径
- Quartz 表单独配置一个 Quartz 专用 Flyway location
- 不建议把 Quartz 官方表和业务表脚本混在同一个发布节奏里

## 应用配置口径

当前工程中的 Quartz 关键配置应保持：

- `org.quartz.jobStore.driverDelegateClass = org.quartz.impl.jdbcjobstore.PostgreSQLDelegate`
- `org.quartz.jobStore.tablePrefix = quartz.QRTZ_`

## 本地执行示例

```bash
docker exec -i batch-postgres \
  psql -U batch_user -d batch_platform -v ON_ERROR_STOP=1 \
  < docs/sql/quartz/Q1__create_quartz_tables_postgres_2_5_2.sql
```
