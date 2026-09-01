# 数据初始化脚本

这里放本地、staging 和测试环境的数据初始化入口。

## 脚本

- `init-kafka-topics.sh`：创建平台默认 Kafka topics。
- `init-tenant-topics.sh`：按租户创建隔离 topic。
- `init-tenant-kafka-acl.sh`：按租户初始化 Kafka ACL。
- `init-minio.sh`：初始化 MinIO bucket 和基础策略。
- `load-system-test-data.sh`：加载系统测试数据。

`sql/` 下是这些入口使用的辅助 SQL。正式 schema 变更请放在 [../../db/migration/](../../db/migration/)。

## 非容器环境

这些脚本不要求必须进 Docker 容器，但需要本机安装对应客户端并通过环境变量指定连接信息：

- Kafka topic 初始化：安装 Kafka CLI，确保 `kafka-topics.sh` 在 `PATH`，或设置 `KAFKA_BIN_DIR=/path/to/kafka/bin` / `KAFKA_TOPICS_BIN=/path/to/kafka-topics.sh`；连接地址用 `KAFKA_BOOTSTRAP_SERVER`。
- MinIO 初始化：安装 `mc`；连接地址和凭据用 `MINIO_ENDPOINT`、`MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD`、`MINIO_BUCKET`。
- 系统测试数据加载：数据库连接用 `PGHOST`、`PGPORT`、`PGUSER`、`PGPASSWORD`、`PLATFORM_DB`、`BUSINESS_DB`；默认使用宿主机 `psql`，也可设置 `BATCH_PSQL_BIN` 或 `BATCH_PG_CLIENT_MODE=docker` 复用 PG 容器内客户端。对象存储用 `BATCH_S3_*`。两种客户端都不可用时明确失败。
