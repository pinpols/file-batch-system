# 代码深度审查报告 - 2026-05-21

审查基线: `main` 当前 HEAD `deca8cf0`，覆盖 2026-05-20 到 2026-05-21 的 55 个 commit，以及当前未提交改动。

当前未提交改动: staged 的 `JobInstanceTerminalStatusApplicationService`/测试 metrics 接入，unstaged 的 `V139__create_trigger_outbox_event_archive.sql` 约束调整，未跟踪的本报告。

说明: 按要求不继续跑测试；本报告以静态代码审查为准。中途已停止正在执行的定向测试进程，未把测试结果作为结论依据。

## P1 必须修

### 1. 当前未提交 V139 会让 Flyway 在 V139 直接失败

位置: `db/migration/V139__create_trigger_outbox_event_archive.sql:44-69`

当前 unstaged diff 删除了 V139 对 `batch.archive_policy.ck_archive_policy_table` 的白名单扩展，并注释说统一到 V141。但 V139 自己随后立即插入 `target_table='trigger_outbox_event'` 的 policy seed。Flyway 顺序是 V139 -> V140 -> V141，V141 还没执行时旧 CHECK 约束仍不允许 `trigger_outbox_event`，所以 V139 的 `INSERT` 会违反约束。

修复: 要么保留 V139 的 DROP+ADD，让它先允许 `trigger_outbox_event`; 要么把 V139 的 seed 移到 V141 之后。不能只把 CHECK 扩展后移、seed 留在 V139。

### 2. DomainEventPublisher 迁移后的单测把 Map 当 JSON 解析

位置: `batch-orchestrator/src/test/java/com/example/batch/orchestrator/application/engine/TaskDispatchOutboxServiceBehaviorTest.java:90`

`DomainEvent.payload()` 已经是 `Map<String,Object>`，但测试多处调用 `parseMsg(capture().payload().toString())`。`Map.toString()` 产物类似 `{priorityBand=HIGH}`，不是 JSON，`JsonUtils.fromJson` 不能稳定解析。这会让行为测试在真正跑到该类时失败，也说明 0520 的 DomainEvent 迁移没有同步测试断言方式。

修复: 直接断言 `capture().payload()`，或把 payload 用 `JsonUtils.toJson(...)` 后再解析。

### 3. JobLifecycleMetrics 使用 `jobCode` 打 `job_type` tag，存在高基数风险

位置:
- `batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/task/JobInstanceTerminalStatusApplicationService.java:68-71`
- `batch-orchestrator/src/main/java/com/example/batch/orchestrator/observability/JobLifecycleMetrics.java:23-24`
- `batch-common/src/main/java/com/example/batch/common/observability/BatchMetricsNames.java:43-45`

指标设计明确写的是 `job_type`，且避免高基数 tag；但当前未提交代码传入的是 `instance.getJobCode()`。`job_code` 通常是租户自定义业务编码，数量和生命周期都不受控，会把 Timer/Histogram 的 time series 放大，尤其 `publishPercentileHistogram()` 会进一步放大成本。

修复: 传入真正的 `job_definition.job_type`，或者把 tag 改名为 `job_code` 并显式接受/限制 cardinality。不建议在默认 job duration histogram 上打 jobCode。

### 4. cleanup 脚本直接 DELETE，绕过刚新增的 archive policy

位置:
- `scripts/db/cleanup-trigger-outbox-events.sql:51-59`
- `scripts/db/cleanup-dead-letter-task.sql:49-57`
- `scripts/db/cleanup-job-execution-log.sql:50-65`

V139/V140/V141 新增了 archive 表和 `archive_policy` 种子，但这三个脚本直接删除热表数据，没有先确认对应 archive 表已经有同一批记录。脚本一旦被 DBA 独立执行，会永久丢失复盘/审计冷数据。

修复: 脚本改成先 `INSERT INTO archive.*_archive SELECT ...`，再按主键 delete；或者加硬性前置检查，只允许在归档调度器已完成对应窗口后执行。

## P2 高风险/需收敛

### 5. afterCommit 内 metrics 异常可能污染业务返回

位置: `batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/task/JobInstanceTerminalStatusApplicationService.java:61-72`

`afterCommit()` 中又查库并注册 Micrometer 指标，没有 `try/catch`。如果 mapper、registry 或 histogram 注册抛异常，业务事务已经提交，但调用方仍可能拿到异常，形成"状态已终态但接口/调度认为失败"的错觉。

修复: `afterCommit` 内捕获异常并打 warn，metrics 不能影响终态写入路径。

### 6. `JobInstanceTerminalStatusApplicationServiceTest` 没真正验证 metrics hook

位置: `batch-orchestrator/src/test/java/com/example/batch/orchestrator/application/service/task/JobInstanceTerminalStatusApplicationServiceTest.java:38-62`

测试直接 `new` service，没有 Spring transaction，也没有手动 `TransactionSynchronizationManager.initSynchronization()`，所以 `registerMetricsAfterCommit()` 会因为 synchronization inactive 直接 return。新增的 `jobLifecycleMetrics` mock 只是构造参数，未验证任何调用。

修复: 单测手动启用/触发 transaction synchronization，并断言 rows > 0 才注册/调用 metrics；rows = 0 不调用。

### 7. partitionNo 的 Guard 与注释不一致

位置: `batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/task/DefaultPartitionLifecycleService.java:71-75`

注释说 `partitionPlan.partitionNo` 为 null 要 fail-fast，但代码先拼字符串: `jobInstanceId + ":" + partitionPlan.getPartitionNo()`。当 partitionNo 为 null 时结果是 `"123:null"`，`Guard.requireText` 仍会通过，无法达到注释中的回退效果。

修复: 先单独校验 `partitionPlan.getPartitionNo() != null`，再拼 idempotencyKey。

### 8. V136/V137 同批 VALIDATE 可能卡住已有异常数据环境

位置:
- `db/migration/V136__job_instance_trigger_source_check_not_valid.sql:22-29`
- `db/migration/V137__validate_job_instance_trigger_source_check.sql:19-23`

约束语义本身合理，但 V137 立即 VALIDATE。只要历史存在 `trigger_request_id IS NULL AND trigger_type <> 'MANUAL'` 的行，部署会在 Flyway 阶段失败。脚本注释给了排查 SQL，但没有数据修复 migration 或预检脚本。

修复: 上线前先跑预检；若已有历史数据，补一条数据修复 migration 或把 VALIDATE 放到确认清理后的独立发布。

### 9. `ChildJobLaunchSupport` 仍有裸 `IllegalStateException`

位置: `batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/task/ChildJobLaunchSupport.java:198-201`

CAS 更新 `expected_partition_count` 失败时抛裸 `IllegalStateException`。这会绕过统一 `BizException/ResultCode` 错误模型，控制台/上游调用方拿不到稳定错误码。

修复: 改为项目统一的 `BizException.of(...)`，错误码表达并发冲突或状态冲突。

## P3 建议

### 10. DomainEvent 未校验 eventKey，但 outbox_event.event_key 是 NOT NULL

位置:
- `batch-common/src/main/java/com/example/batch/common/event/DomainEvent.java:26-42`
- `batch-orchestrator/src/main/java/com/example/batch/orchestrator/event/OutboxDomainEventPublisher.java:37`
- `batch-orchestrator/src/main/resources/mapper/OutboxEventMapper.xml:23-48`

`DomainEvent` 校验了 tenantId/aggregateType/eventType，但没有校验 eventKey。当前调用方大多自己补 key，短期不一定出问题；但这个抽象的目标是统一 outbox 写入，应该把 DB NOT NULL/幂等约束前移到构造期，避免未来新增调用方把 null 写到 mapper 后才失败。

修复: 在 `DomainEvent` canonical constructor 中 require nonblank eventKey，或在 builder.build() 统一生成 fallback key。

## 已核对的已修复项

- V133 SQL 索引列名错误已由 `68bfb750` 修复，当前不再作为问题。
- JobInstance cursor 模式 offset 二次分页及 `sortBy=duration` 已由 `456c70a9` 修复，当前不再作为问题。
- Compensation 业务事务回滚/FAILED 状态独立持久化问题已由 `07d832cc` 修复，当前不再作为问题。

## 总结

当前最危险的是未提交的 V139 修改: 它会把原本可执行的迁移改成 V139 自身违反 CHECK 约束。其次是 metrics 接入的 tag 维度和 afterCommit 异常隔离，这两项如果直接合入会把观测面和业务返回都带进不稳定状态。DBA cleanup 脚本需要补"先归档后删除"的硬保护，否则新建 archive policy 反而容易给人工清理造成误导。
