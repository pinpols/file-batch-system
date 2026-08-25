# 控制面热路径优化核验（2026-08-25）

## 本轮结论

本轮针对实例聚合、任务领取、结果写入和历史表生命周期完成代码核验，并落地一个低风险优化：

- 普通非 DAG 实例的 task report 改为数据库单行状态聚合，避免把实例全部分区状态加载到 JVM；
- DAG 实例继续使用 `(partition_id, partition_status)` 轻量投影，因为节点推进需要分区与节点映射；
- 备用 READY task 查询补充 `FOR UPDATE SKIP LOCKED`，防止未来启用数据库拉取路径时多实例重复拿到同一批任务；
- 新增 `idx_job_partition_instance_status`，支持实例状态聚合定位。

## 保留的设计边界

当前生产主链路是 Kafka 携带明确 taskId，Worker 通过 `READY + version CAS` 认领；不新增全表扫描式任务分发器。

结果 `report-batch` 仍保持逐项独立事务。该取舍保护 DAG、补偿、重试和幂等语义；是否继续做 set-based 终态写入，必须先有压力数据证明。

全量分区扫描保留为 DAG 推进和一致性校验用途，不作为普通实例热路径的默认实现。

## 后续验证门槛

使用相同 Docker 基础环境分别压测 1k、1w、10w task storm，并记录：

1. `job_partition` 聚合 SQL 的 p95/p99 与 `EXPLAIN (ANALYZE, BUFFERS)`；
2. PostgreSQL CPU、IO、WAL 增量、锁等待和连接池占用；
3. task report 延迟、Kafka lag、outbox backlog、终态收敛时间；
4. DAG 与非 DAG 混压时的成功率、重复回报率和状态一致性；
5. archive lag、dead tuple、autovacuum 延迟和未来分区维护失败告警。

只有在 report 写放大仍是瓶颈时，才评估简单非 DAG 作业的 set-based 终态更新；复杂 DAG、补偿和重试继续沿用逐项事务。
