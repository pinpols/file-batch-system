# Trigger 排空优化验证（2026-09-02）

## 背景

10 万 trigger launch 压测中，99,517 个已接收请求最终全部完成，但控制面排空约为 18-20/s。
atomic worker 的 p95 执行仅约 66ms，瓶颈在 trigger outbox → Kafka → Orchestrator 的实例创建控制面，而不是 worker 执行。

## 改动

1. `TriggerLaunchConsumer` 不再二次查询 `job_instance` 并 best-effort 回写 `trigger_request`。`DefaultLaunchService` 已在主事务路径中唯一推进 `LAUNCHED`、`DUPLICATE` 或 `REJECTED` 状态；删除二次写减少每条消息一次 SELECT 和 UPDATE，并避免 gate skip 被错误标记为 `LAUNCHED`。
2. 每个 Orchestrator 实例的 trigger consumer 默认并发从 4 调整为 6；Helm 默认 2 副本，topic 基线为至少 12 分区。
3. topic 初始化脚本、Compose、Helm、环境样例和运行手册统一 `KAFKA_PARTITIONS_TRIGGER_LAUNCH=12` / `BATCH_TRIGGER_CONSUMER_CONCURRENCY=6`。
4. 新增 `batch.trigger.launch.kafka.queue.age` 和 `batch.trigger.launch.consume.duration` 直方图，以及 queue age p95 超过 30 秒的告警。
5. 正常成功日志和 `result_version` 写入日志降为 DEBUG，避免高压时 INFO 日志放大 IO。

## 本机真实链路复验

环境：本机单 Orchestrator、Kafka 单 broker、atomic SQL worker；2026-09-02。

| 项目 | 结果 |
|---|---|
| Kafka topic 分区 | `batch.trigger.launch.v1` 从 3 安全扩容到 12 |
| 实际 consumer 分配 | 6 个 listener 分配到全部 12 分区 |
| P2 隔离租户 trigger 压测 | 1000 个并发提交中 346 个被入口接收，346/346 终态 `SUCCESS` |
| 入口拒绝 | 654 个 HTTP 429，原因是 `BATCH_TRIGGER_API_LAUNCH_MAX_CONCURRENCY=64` 的保护闸门，不是 Kafka 或 Orchestrator 排空失败 |
| Kafka lag | 0 |
| 临时数据 | job 实例和 `p2-fairness-profile` quota 均清理为 0 |
| 指标导出 | queue age / consume duration 均有 346 条样本和 Prometheus histogram bucket |

本轮是结构和真实链路复验，不将该并发 burst 的延迟数字与此前平滑 100 RPS 的容量结果混为一谈。下一次容量验收应以平滑速率、独立入口 admission 配置和完整 10 万 profile 重新取数。

## 部署约束

Kafka 分区只能增加，不能缩小。扩容前确认：

`topic partitions >= orchestrator replicaCount × BATCH_TRIGGER_CONSUMER_CONCURRENCY`

默认值为 `12 >= 2 × 6`。扩副本或提高每实例并发时，必须先扩 topic 分区并确认 consumer group 已完成 rebalance，再提升流量。
