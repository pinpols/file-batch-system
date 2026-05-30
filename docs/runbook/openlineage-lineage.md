# OpenLineage 血缘 emitter

> P1(2026-05-30,v0.1)。对标差距分析的"数据血缘 Lineage"项。在 workflow_run 终态向 OpenLineage
> 兼容端点(如 Marquez)发血缘 RunEvent,供血缘平台构图。**默认关闭**。

## 它做什么

`OpenLineageEmitter`(batch-orchestrator)在 workflow_run 进入终态(SUCCESS / FAILED / TERMINATED /
*_DRY_RUN)时,emit 一条 spec-compliant OpenLineage RunEvent:

- SUCCESS / SUCCESS_DRY_RUN → `eventType=COMPLETE`
- FAILED / FAILED_DRY_RUN / TERMINATED → `eventType=FAIL`
- `run.runId`:由 workflow_run id 确定性派生(name-based UUID),将来补 START 事件时与 COMPLETE 同 runId 成对
- `job.namespace`:配置的 namespace;`job.name`:`workflow.<tenant>.def<definitionId>`
- run facet `nominalTime`(startedAt / finishedAt);job facet `documentation`(workflow_run / tenant / bizDate / traceId)

## 设计要点

- **不引 openlineage-java 客户端**:SB4 + JDK25 兼容性未验,事件是文档化 JSON,用 Jackson 手搓 + JDK
  HttpClient 发即可,零新依赖(同 P1-B 不贸然引 Resilience4j 的教训)。
- **绝不阻塞主链**:挂在 `WorkflowTerminalOutboxService.writeTerminalEvent` 的 `afterCommit` 同步里
  (终态真提交才发,回滚不发假血缘);emit 提交到独立 daemon 线程池,池满即丢,所有异常 swallow。
- **可观测**:counter `openlineage.emit{outcome, status}`,outcome ∈ success / http_error / error /
  rejected / interrupted。

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
    emit-threads: 2
```

启用:置 `batch.openlineage.enabled=true` + 填 `endpoint`。endpoint 空时即使 enabled 也 no-op。

## v0.1 不做的(后续升级路径)

- **inputs / outputs 数据集**:当前 `inputs=[]` `outputs=[]`。下一步从 job_instance.relatedFileId /
  file_record(trace_id 反查)/ jdbc_mapped_import/export 的 schema.table / node output 填真实数据集,
  这是血缘图的核心价值。
- **START 事件**:当前只发终态。补 RUNNING 钩子(WaitingPartitionDispatchScheduler.markRunning)发
  START,让 Marquez 算 duration。runId 已确定性派生,成对没问题。
- **节点级血缘**:当前 workflow_run 粒度。可下钻到 workflow_node_run(每个 node 一个 job)。
- **换 openlineage-java 官方 client**:需要 facets 深度集成时,做 SB4 兼容 spike 后替换内部实现,
  emitter API 不变。
- **可靠投递**:当前 fire-and-forget 丢即丢。要求不丢时,改走 outbox(已有 `batch.workflow.terminal.v1`
  topic 带全部字段,加个消费者转 OpenLineage 即可)。
