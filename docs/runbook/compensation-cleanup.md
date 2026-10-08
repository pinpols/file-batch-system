# 补偿失败诊断与处置

> 本文只覆盖当前实现可支持的诊断与处置边界。`compensation_command` 是补偿命令状态主记录；V63 创建了 `compensation_checkpoint` 表，但当前 `DefaultCompensationService` 未写入该表，因此不得依赖它判断某一步已提交或回滚。当前实现没有按 handler 逆向清理已提交副作用的通用机制。

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

`RUNNING` 命令超过配置的 stale-running timeout 后，`StaleCompensationCommandReconciler` 会将其标为 `FAILED` 并写入超时错误。它不会撤销 handler 已提交的业务副作用；检查配置 `batch.compensation.stale-running-reconciler.*`、Orchestrator 日志及相关 job/task/outbox 状态后再决定后续动作。

## 恢复边界

1. 不要直接删除或改写 `job_instance`、`job_partition`、`job_task`、`pipeline_step_run`、`outbox_event`、`compensation_command` 状态。直接 SQL 会绕过状态机 CAS、Outbox 原子性和审计。
2. 不要把 `compensation_checkpoint` 当作已接通的执行日志，也不要据其生成 DELETE/UPDATE 操作。
3. 确认根因已修复、目标状态允许再次执行，并取得业务审批后，才通过受认证的内部接口 `POST /internal/compensations` 提交新的补偿命令。该接口会创建新命令；它不是撤销或修复旧命令的接口。请求字段以 `CompensationController.CompensationRequest` 为准。
4. 如果副作用已部分提交且无法由现有业务接口安全纠正，停止自动重试，保存 `tenant_id`、`command_no`、`trace_id`、审批号、相关实例 ID 和时间线，升级给后端维护者制定经审计的修复方案。

## 事后复核

- 复核失败命令、错误类别、目标实例终态及相关 Outbox 事件是否一致。
- 若频繁出现部分副作用或需人工数据修复，登记为实现缺口；应先设计可审计的补偿/恢复能力，再更新本手册，不得以手工 SQL 作为常规流程。

## 相关

- 代码：`batch-orchestrator/.../application/service/governance/DefaultCompensationService.java`
- Stale 命令回收：`batch-orchestrator/.../infrastructure/scheduler/StaleCompensationCommandReconciler.java`
- 命令表：`db/migration/V13__create_compensation_and_step_runtime.sql`
- V63 checkpoint 表结构：`db/migration/V63__compensation_checkpoint.sql`（当前未接入命令执行写入）
