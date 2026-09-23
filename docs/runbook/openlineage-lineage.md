# OpenLineage 血缘 emitter

> v0.2。workflow_run 终态先写事务 Outbox，再经专用 Kafka topic 向 OpenLineage
> 兼容端点(如 Marquez)可靠投递 RunEvent。**默认关闭**。

## 它做什么

`OpenLineageEmitter`(batch-orchestrator)在 workflow_run 进入终态(SUCCESS / FAILED / TERMINATED /
*_DRY_RUN)时,emit 一条 spec-compliant OpenLineage RunEvent:

- SUCCESS / SUCCESS_DRY_RUN → `eventType=COMPLETE`
- FAILED / FAILED_DRY_RUN / TERMINATED → `eventType=FAIL`
- `run.runId`:由 workflow_run id 确定性派生(name-based UUID),将来补 START 事件时与 COMPLETE 同 runId 成对
- `job.namespace`:配置的 namespace;`job.name`:`workflow.<tenant>.def<definitionId>`
- run facet `nominalTime`(startedAt / finishedAt);job facet `documentation`(workflow_run / tenant / bizDate / traceId)
- `inputs` / `outputs`:基于 BFS 热表推导文件级 dataset:
  - `file_category=INPUT` → `inputs`
  - 其它 BFS 文件产物(`OUTPUT` / `INTERMEDIATE` / `ARCHIVE`) → `outputs`
  - dataset 来源覆盖 `workflow_run.related_job_instance_id` 关联的 `job_instance.related_file_id`、`pipeline_instance.file_id`、同 `trace_id` 的 `file_record`
- dataset facet `bfsFile`:包含 `fileId/tenantId/fileCategory/fileFormatType/fileStatus/fileSizeBytes/checksum/traceId`

## 设计要点

- **不引 openlineage-java 客户端**:SB4 + JDK 21 兼容性未验,事件是文档化 JSON,用 Jackson + JDK
  HttpClient 发即可,零新依赖(同 P1-B 不贸然引 Resilience4j 的教训)。
- **事务与外部端点隔离**:`WorkflowTerminalOutboxService` 只在状态事务中写 Outbox；relay 投递
  `batch.workflow.terminal.v1`。血缘消费者同步调用 endpoint，仅 HTTP 2xx 后提交 offset；失败时
  `nack` 并暂停该血缘分区，不回滚或阻塞 workflow 状态事务。
- **重放幂等**:`runId` 由 workflow_run id 确定性派生。Kafka 至少一次重投会生成同一 runId，接收端按
  OpenLineage run 语义合并。
- **dataset 查询失败不影响 emit**:dataset mapper 异常只记录 `openlineage.emit{outcome=dataset_error}` 并降级为
  `inputs=[]/outputs=[]`。
- **可观测**:counter `openlineage.emit{outcome, status}`,outcome ∈ success / http_error / error /
  rejected / interrupted / dataset_error。

## 配置(默认全关)

```yaml
batch:
  openlineage:
    enabled: false                       # 总开关,默认关
    endpoint: ""                         # 如 http://marquez:5000/api/v1/lineage
    namespace: file-batch-system
    producer: https://github.com/pinpols/file-batch-system
    connect-timeout-ms: 2000
    request-timeout-ms: 3000
    consumer-group-id: batch-openlineage-emitter
    retry-backoff-ms: 5000
```

启用:置 `batch.openlineage.enabled=true` + 填 `endpoint`。endpoint 空时即使 enabled 也 no-op。

## 后续升级路径

- **字段级 / 记录级血缘**:当前只承诺 BFS 文件级 dataset,不展开字段映射、SQL column lineage 或逐行追踪。
- **业务表 dataset**:当前不解析 `jdbc_mapped_import/export` 的 `schema.table` 为数据库 dataset;后续可在模板契约稳定后补。
- **START 事件**:当前只发终态。补 RUNNING 钩子(WaitingPartitionDispatchScheduler.markRunning)发
  START,让 Marquez 算 duration。runId 已确定性派生,成对没问题。
- **节点级血缘**:当前 workflow_run 粒度。可下钻到 workflow_node_run(每个 node 一个 job)。
- **换 openlineage-java 官方 client**:需要 facets 深度集成时,做 SB4 兼容 spike 后替换内部实现,
  emitter API 不变。
