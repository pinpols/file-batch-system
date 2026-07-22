# 数据初始化脚本

这里放本地、staging 和测试环境的数据初始化入口。

## 脚本

- `init-kafka-topics.sh`：创建平台默认 Kafka topics。
- `init-tenant-topics.sh`：按租户创建隔离 topic。
- `init-tenant-kafka-acl.sh`：按租户初始化 Kafka ACL。
- `init-minio.sh`：初始化 MinIO bucket 和基础策略。
- `load-system-test-data.sh`：加载系统测试数据。

`sql/` 下是这些入口使用的辅助 SQL。正式 schema 变更请放在 [../../db/migration/](../../db/migration/)。
