# Worker 消费背压持续饱和

> 优先级 P1 · 适用告警：`WorkerBackpressureSaturated`

## TL;DR

worker 的执行许可持续耗尽，Kafka listener 会主动 pause，目的是保护进程和避免 claim 后无界堆积。先确认是下游变慢还是并发配置过低，再决定降载、恢复依赖或扩容；不要只盲目调大 `max-concurrent-tasks`。

## 怎么发现

- `batch_worker_semaphore_available{workerType="..."} == 0` 持续 10 分钟触发 `WorkerBackpressureSaturated`。
- `batch_worker_consumer_pause_total` 持续增长且 `resume_total` 长时间不跟上，说明 listener 反复因许可耗尽暂停。
- 同时检查 `BatchTaskClaimLatencyHigh`、`BatchPipelineStepExecutionLatencyHigh`、`BatchKafkaConsumerLagHigh` 和下游存储/HTTP 告警。

## 怎么定位

1. 确认是哪个 worker 类型和实例：
   ```promql
   batch_worker_semaphore_available{workerType=~"IMPORT|EXPORT|PROCESS|DISPATCH|ATOMIC"}
   sum by (workerType) (rate(batch_worker_consumer_pause_total[10m]))
   sum by (workerType) (rate(batch_worker_consumer_resume_total[10m]))
   ```
2. 看执行耗时和任务状态：
   ```sql
   select task_type, task_status, count(*)
     from batch.job_task
    where task_status in ('READY', 'RUNNING')
    group by task_type, task_status;
   ```
   重点确认是否存在大量长时间 RUNNING、lease renew circuit OPEN、下游存储超时或 report outbox 积压。
3. 区分两类原因：
   - 执行耗时整体上升：优先恢复 PG/对象存储/下游 HTTP，避免放大并发。
   - 执行耗时正常但许可长期为零：检查 `batch.worker.max-concurrent-tasks`、JVM heap、连接池上限和实例数是否匹配。

## 怎么恢复

### 方案 A：无损恢复

恢复异常的下游依赖，等待在途任务自然释放许可；确认 `available` 回升、`resume_total` 增长、Kafka lag 开始下降。

### 方案 B：有控制地降载或扩容

降低新任务进入速率或增加同 worker 类型实例。只有在 heap、JDBC/HTTP 连接池和下游容量均有余量时，才逐步提高 `max-concurrent-tasks`，每次变更后观察 10 分钟。

### 方案 C：任务级故障处置

对确认已失联的任务走 orchestrator 的超时/治理流程，不直接改 `job_task` 状态；确认 lease 回收、重投和终态 CAS 正常后再恢复流量。

## 验收

- `batch_worker_semaphore_available` 不再持续为 0；
- pause/resume 进入可解释的稳定比例；
- Kafka lag、claim latency、report outbox 和终态完成率回到基线；
- 未通过调大并发掩盖下游故障，也没有直接 SQL 改状态。

## 关联

- 代码：`batch-worker/core/.../AbstractTaskConsumer.java`
- 规则：`docker/observability/prometheus-batch-rules.yml`、Helm 同步副本
- 上一级：[`README.md`](README.md)
