# 补偿失败诊断与处置

> 本文只覆盖当前实现可支持的诊断与处置边界。`compensation_command` 是补偿命令状态主记录；V63预留的 `compensation_checkpoint` 从未接通执行写入，已在V347退役，因此不得依赖它判断某一步已提交或回滚。当前实现没有按 handler 逆向清理已提交副作用的通用机制。

## 适用场景

- `batch.compensation_command.command_status` 为 `FAILED`。
- 命令长时间保持 `RUNNING`，或补偿产生了需要人工核对的业务副作用。

## 只读诊断

按租户和命令号查询命令状态、审批、错误和结果摘要：

```sql
SELECT tenant_id, command_no, compensation_type, target_id,
       related_job_instance_id, related_file_id, approval_id, operator_id,
       command_status, error_code, error_message, result_summary,
       created_at, finished_at
  FROM batch.compensation_command
 WHERE tenant_id = '<tenant>'
   AND command_no = '<command-no>';
```

补充查询同租户、同目标的命令时间线：

```sql
SELECT command_no, compensation_type, target_id, command_status,
       error_code, error_message, trace_id, created_at, finished_at
  FROM batch.compensation_command
 WHERE tenant_id = '<tenant>'
   AND target_id = <target-id>
 ORDER BY created_at DESC
 LIMIT 20;
```

`RUNNING` 命令超过配置的 stale-running timeout 后，`StaleCompensationCommandReconciler` 会将符合条件且未被锁定的命令标为 `FAILED` 并写入超时错误。运行中的 handler 持有命令行锁，扫描会跳过它；超过阈值并不意味着该次扫描一定回收。对账先完成时 handler 不会再启动，状态条件也阻止终态被覆盖。对账不会撤销已提交的业务副作用；检查配置 `batch.compensation.stale-running-reconciler.*`、Orchestrator 日志及相关 job/task/outbox 状态后再决定后续动作。

当补偿参与调用方事务时，handler 失败或外层事务在 handler 成功后回滚，失败记录在外层事务完成、命令锁释放后以独立事务写入。事务完成结果未知或失败记录写入异常时应检查 Orchestrator 错误日志和命令状态；stale 对账仍是异常恢复入口。

## JOB/BATCH 补偿的异步受理

JOB/BATCH 启动补偿时，命令成功表示启动请求及冻结载荷已和 Outbox 一起提交，不表示新作业已经创建或执行成功。使用 `result_summary.launchRequestId` 关联同租户的 `trigger_request.request_id`，再通过其内部 ID 查询 `workflow_run.trigger_request_id` 和 JOB 节点的 `job_instance_id`。`newInstanceNo`、`related_job_instance_id` 在尚未创建子 Job 时可以为空；Workflow Run ID 不能当作 Job 实例号。

核对受理请求、实际根运行与子作业：

```sql
SELECT tr.request_id, tr.request_status, wr.id AS workflow_run_id,
       wr.run_status, nr.node_code, nr.run_seq,
       ji.id AS job_instance_id, ji.instance_no, ji.instance_status
  FROM batch.trigger_request tr
  LEFT JOIN batch.workflow_run wr
    ON wr.tenant_id = tr.tenant_id AND wr.trigger_request_id = tr.id
  LEFT JOIN batch.workflow_node_run nr ON nr.workflow_run_id = wr.id
  LEFT JOIN batch.job_instance ji
    ON ji.tenant_id = tr.tenant_id AND ji.id = nr.job_instance_id
 WHERE tr.tenant_id = '<tenant>'
   AND tr.request_id = '<launchRequestId>'
 ORDER BY wr.id, nr.node_code, nr.run_seq;
```

批量日重放使用稳定的 `replay-{sessionId}-{entryId}` 请求身份。派发器会绑定该请求下的实际当前子 Job；明确 `REJECTED/GIVE_UP` 且尚未创建执行的请求会使对应条目失败并重新核算会话。创建请求前就失败的提交也会在后续派发周期按失败条目核算；没有启动请求不表示需要人工改会话状态。暂时的 Outbox/Kafka 故障仍由原请求恢复，不能因 `newInstanceNo` 为空就反复提交新的补偿命令。历史重放和失败补跑按实例已冻结的日历与业务日选择候选，当前 Job 日历配置不决定历史归属。

自动死信重放仅处理符合配置、到期且仍有预算的 SYSTEM 类记录。来源为作业分区且父作业或其最新关联 Workflow 暂停时，该记录等待恢复，不消耗重放次数，也不占用自动扫描批次；恢复后按原到期时间参与扫描。父执行已终态或来源缺失的记录仍会进入收口，不应手工更改其状态。

## 恢复边界

1. 不要直接删除或改写 `job_instance`、`job_partition`、`job_task`、`pipeline_step_run`、`outbox_event`、`compensation_command` 状态。直接 SQL 会绕过状态机 CAS、Outbox 原子性和审计。
2. 分步checkpoint表已退役，不据其生成DELETE/UPDATE操作；以补偿命令及实际业务副作用核查为准。
3. 确认根因已修复、目标状态允许再次执行，并取得业务审批后，才通过受认证的内部接口 `POST /internal/compensations` 提交新的补偿命令。该接口会创建新命令；它不是撤销或修复旧命令的接口。请求字段以 `CompensationController.CompensationRequest` 为准。
4. 如果副作用已部分提交且无法由现有业务接口安全纠正，停止自动重试，保存 `tenant_id`、`command_no`、`trace_id`、审批号、相关实例 ID 和时间线，升级给后端维护者制定经审计的修复方案。

## 非幂等外部动作与结果不明

对 HTTP POST、Shell、stored-proc 或其它无法用稳定业务键保证幂等的外部动作，若远端可能已提交但 Worker 未收到响应，不能仅因 Orchestrator 将本次 attempt 标记失败就再次执行。应在**首次运行前**将对应 Job 的 `retry_policy` 配置为 `NONE`；实例创建后 `executionPolicy` 已冻结，之后修改 Job 定义不会改变该实例的重试策略。`NONE` 会将失败送入 dead-letter，`max_replay_count=0` 且不安排自动重放；Worker 内部 HTTP 方法不重试的保证不能代替这项作业级配置。`TaskCapability.idempotent=false` 当前是 Worker 能力摘要，不会自动覆盖 Job 的 retry policy。

结果不明时，先通过远端业务键、目标回执、审计记录或对账接口确认副作用是否已提交：

1. 已提交：保持 Job/Workflow 的失败及 UNKNOWN 事实，记录远端凭证和核对结果；由业务所有者按具体渠道的既有业务流程修复本地结果。平台没有通用接口可把 UNKNOWN 执行改记成功，也不能把已执行结果改写成“未执行”。
2. 明确未提交：授权操作员可在 Console 对对应死信提交“已核实未执行”确认、原因和证据引用，再发起一次性补偿。服务端记录认证主体与证据，并要求 DLQ 来源精确匹配唯一 UNKNOWN 任务；原 Workflow/Job/Partition/Task 保持失败终态。新 Workflow 启动请求、Launch Outbox、DLQ 成功状态及授权与唯一根请求的绑定在同一业务事务提交；DLQ 的成功状态表示恢复请求已登记，不等于新业务执行成功。重复授权、来源不匹配、多条 UNKNOWN、普通已知失败套用核实声明均失败关闭。普通内部死信重放接口不得直接恢复 UNKNOWN 任务。不要直接修改运行表或绕过 Console/认证 API。
3. 仍无法确认：保持死信，不发起重放；保存运行身份和两端时间线，升级人工处置。不得以平台操作将“仍不确定”伪造为“未执行”。

实际非幂等动作开始时还会核对授权来源。文件分发在渠道尝试的状态事务内消费授权；其它 Worker／SDK
通过开始接口，由 Orchestrator 按原任务版本、父实例、租户、业务日和实际根请求做一次性 CAS，
授权消费与本次任务的开始标记在同一事务提交。另一根请求即使复制授权标识也不能开始；开始写库失败会一并回滚授权消费。
同一 worker／invocation 的重复开始保持幂等，进程崩溃不重置已消费的授权。

普通可重放死信的默认重放行为仍不能覆盖 UNKNOWN；仅上述经认证并获批的补偿路径可登记一次性核验授权并创建新根运行。该路径记录审计，不修改原任务状态，也不提供将任务改记成功的能力。实现与真实 PostgreSQL 验证见[执行日志](../plans/workflow-daily-integration-execution-log-2026-10-14.md#2026-10-21-b11-r25-unknown-终态来源下审批后创建新-workflow-根运行)及[WR-04 定向复验](../verifications/wdi-functional-closure-2026-10-25.md)。

该端点不会检查远端是否已产生副作用；审计用 `idempotencyKey` 当前也不参与服务端 CAS 或去重，因此不能把它当成防重复执行保证。禁止通过直接改 `retry_schedule`、`dead_letter_task` 或运行表状态来绕过上述处置边界。

## 事后复核

- 复核失败命令、错误类别、目标实例终态及相关 Outbox 事件是否一致。
- 若频繁出现部分副作用或需人工数据修复，登记为实现缺口；应先设计可审计的补偿/恢复能力，再更新本手册，不得以手工 SQL 作为常规流程。

## 相关

- 代码：`batch-orchestrator/.../application/service/governance/DefaultCompensationService.java`
- Stale 命令回收：`batch-orchestrator/.../infrastructure/scheduler/StaleCompensationCommandReconciler.java`
- 命令表：`db/migration/V13__create_compensation_and_step_runtime.sql`
- V63 checkpoint 表结构：`db/migration/V63__compensation_checkpoint.sql`（当前未接入命令执行写入）

## DLQ 补偿提交边界

普通 DLQ 补偿的重放副作用、Outbox 和补偿 SUCCESS 记账共同提交；成功记账失败时共同回滚，失败命令证据独立保留。直接人工重放、后台扫描仍各项独立提交，不能把补偿内部路径重新改为独立 REQUIRES_NEW。

自动扫描的旧 GIVE_UP 意图不得覆盖 SUCCESS；同一未知外部结果源任务即使有两条死信，并发核实重放也只能登记一个新根请求。命令号、死信成功与业务再次执行成功应分别核对。事务约束见[ADR-006 当前边界](../architecture/adr/ADR-006-compensation-requires-new.md#2026-10-26-边界修正)，真实 PostgreSQL 证据见[当轮验证](../verifications/backend-bug-remediation-2026-10-26.md#全工程续审补偿与死信)。
