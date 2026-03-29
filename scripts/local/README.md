# 本地脚本说明

这里的脚本主要用于本地联调、环境初始化、巡检和故障自愈。

## 常用脚本

- `run-e2e-tests.sh`：运行 `batch-e2e-tests`
- `start-all.sh`：启动本地相关服务
- `stop-all.sh`：停止本地相关服务
- `inspect-all.sh`：依次执行服务、数据库、Worker 巡检
- `inspect-observability.sh`：巡检服务健康、指标和 Kafka lag
- `inspect-db.sh`：巡检数据库、Flyway、Outbox、死信和重试积压
- `inspect-workers.sh`：巡检 Worker 排空、心跳失联和孤儿任务
- `init-kafka-topics.sh`：初始化 Kafka topic
- `init-minio.sh`：初始化 MinIO 资源
- `load-system-test-data.sh`：加载系统测试数据

## 自愈脚本

- `heal-drain-timeout.sh`：处理超时的 DRAINING Worker
- `heal-dead-letters.sh`：重放新的死信任务
- `heal-retry-tasks.sh`：重放指定条件下的失败任务（job_task 粒度）
- `heal-retry-partitions.sh`：重放指定条件下的失败分区（job_partition 粒度）
- `heal-stuck-outbox.sh`：重置卡住的 Outbox 事件
- `trigger-compensation.sh`：手工触发补偿（Console API）

## 运行前提

- 大部分脚本默认依赖 Docker / Docker Desktop、PostgreSQL、Kafka、MinIO 或对应的本地容器
- 使用 Testcontainers 的脚本通常需要 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` 和 `DOCKER_API_VERSION`
- `start-all.sh` / `stop-all.sh` 默认使用 `.env.local`，如需切换环境可设置 `COMPOSE_ENV_FILE=.env.test` 或 `COMPOSE_ENV_FILE=.env.prod`
- 如果脚本有额外环境变量要求，直接看脚本头部注释

## 常见顺序

1. 先执行 `start-all.sh` 或准备好本地依赖
2. 再执行 `init-kafka-topics.sh`、`init-minio.sh`、`load-system-test-data.sh`
3. 本地联调时运行 `run-e2e-tests.sh`
4. 最后用 `inspect-all.sh` 做一次巡检
5. 出现异常时再考虑对应的 `heal-*.sh`

## 相关文档

- [../README.md](../README.md)
- [../../docs/testing/README.md](../../docs/testing/README.md)
- [../../docs/testing/test-plan.md](../../docs/testing/test-plan.md)
