# Compensation 失败清理 runbook

> 针对 v3 A-3.1（DefaultCompensationService 无 saga 反向链）的运维 runbook。
> 触发场景：补偿执行中途失败，残留残留任务 / 分区 / 新 job_instance 需要人工清理。
> 依赖：V63 `compensation_checkpoint` 表已迁移。

## 何时用

以下 alert 触发时：

- `batch.compensation.failed{handler=*}` Counter 非零
- 或手动接到排障单："compensation failed，有 job_instance 挂在 PENDING/RUNNING"

先看 `compensation_checkpoint` 表，再按对应 handler 的清理路径逆向操作。

## 排查入口

```sql
-- 1. 找出所有失败补偿
SELECT tenant_id, compensation_id, handler_code, step_order, step_name,
       status, error_message, created_at, updated_at
FROM batch.compensation_checkpoint
WHERE status = 'FAILED'
ORDER BY created_at DESC
LIMIT 50;

-- 2. 对每个失败 compensation_id，取其完整 checkpoint 链
SELECT step_order, step_name, status, payload_json, error_message, updated_at
FROM batch.compensation_checkpoint
WHERE tenant_id = :t AND compensation_id = :cid
ORDER BY step_order;
```

## 按 handler 清理路径

### JOB handler · rerunJob 失败

**典型现象**：`checkpoint.step_name='create_rerun_instance'` 已 COMMITTED，
但后续步骤 FAILED → 新 job_instance 已生成但未被 orchestrator 认识。

**清理步骤**：
1. 从 checkpoint.payload_json 提取新生成的 `job_instance_id`
2. 查该 instance 状态：
   ```sql
   SELECT id, tenant_id, job_code, biz_date, instance_status, run_attempt
   FROM batch.job_instance
   WHERE tenant_id = :t AND id = :new_instance_id;
   ```
3. 若 `instance_status IN ('CREATED','WAITING')` 且无关联 outbox_event（看 `event_outbox` 无对应聚合）→ 安全删除：
   ```sql
   DELETE FROM batch.job_instance
   WHERE tenant_id = :t AND id = :new_instance_id
     AND instance_status IN ('CREATED','WAITING');
   ```
4. 若 `instance_status = 'RUNNING'` 且已派发 → **不要删**，改为 terminate：
   ```sql
   UPDATE batch.job_instance
   SET instance_status = 'TERMINATED', updated_at = now()
   WHERE tenant_id = :t AND id = :new_instance_id;
   ```
5. 把 checkpoint 标 ROLLED_BACK：
   ```sql
   UPDATE batch.compensation_checkpoint
   SET status = 'ROLLED_BACK', updated_at = now()
   WHERE tenant_id = :t AND compensation_id = :cid AND step_name = 'create_rerun_instance';
   ```

### STEP handler · 单节点重跑失败

**典型**：`step_name='mark_step_retryable'` 已 COMMITTED；`step_name='enqueue_step_retry'` FAILED。

**清理**：
1. 读 payload_json 拿 `step_run_id`
2. 检查 `pipeline_step_run`：若 `step_status = 'RETRYING'`，置回 `FAILED` 并清重试计数：
   ```sql
   UPDATE batch.pipeline_step_run
   SET step_status = 'FAILED', retry_count = retry_count - 1, updated_at = now()
   WHERE id = :step_run_id AND step_status = 'RETRYING';
   ```
3. checkpoint 标 ROLLED_BACK

### PARTITION handler · 分区补偿失败

**典型**：重新派发某批分区的过程中断，新 partition / task 已写但 outbox 没发。

**清理**：
1. 读 payload_json 拿到 `new_partition_ids[]` 和 `new_task_ids[]`
2. 按 ID 查 `job_partition.partition_status` 和 `job_task.task_status`
3. 若都在 `CREATED` → 直接删；若已推到 `READY/RUNNING` → terminate
4. checkpoint 标 ROLLED_BACK

## 通用安全原则

1. **先查后动**：任何 DELETE / UPDATE 前先 SELECT 出精确行数预估；>100 行一律停下问组长
2. **事务包起**：
   ```sql
   BEGIN;
   -- delete / update
   -- 再查一次验证
   -- COMMIT 或 ROLLBACK
   ```
3. **留痕**：所有手工操作的 SQL 和产生时间贴到 incident 工单；checkpoint 改为 ROLLED_BACK 时 `error_message` 填写操作人 + 工单号
4. **不要**直接操作 `outbox_event` 表——若有悬空消息让 outbox_forwarder 自然 TTL 或 dead-letter 走正常回路
5. **不要**跨租户批量动作——每次 SQL 都带 `tenant_id = :t`

## 事后复盘

- 每月汇总 `status='FAILED'` 的 checkpoint，按 handler_code / step_name 聚合
- 频繁失败的步骤列入 v4 治理（考虑引入真 saga 反向链）
- 若某 step 的失败率 > 1%，说明需要把手工清理改成代码层自动回滚

## 相关

- v3 分析：`docs/analysis/deep-issue-analysis-v3.md` A-3.1
- migration：`db/migration/V63__compensation_checkpoint.sql`
- 代码入口：`DefaultCompensationService`（后续集成 checkpoint 写入）
