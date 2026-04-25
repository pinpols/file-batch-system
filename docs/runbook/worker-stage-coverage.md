# 三类 Worker 全 Stage 真实覆盖 — 端到端验证手册

> 验证日期：2026-04-25
> 验证目标：IMPORT / EXPORT / DISPATCH 三个 worker 模块的**全部** stage（含异常路径）
> 在 `local` profile 下使用真实 seed 数据跑通。

## 1. 覆盖矩阵

实际跑过的 stage 与对应证据：

| Worker | Stage | Step Code | Happy Path | 异常 Path |
|---|---|---|---|---|
| **IMPORT** | RECEIVE | `IMPORT_RECEIVE` | ✅ | — |
| | PREPROCESS | `IMPORT_PREPROCESS` | ✅ | — |
| | PARSE | `IMPORT_PARSE` | ✅ | — |
| | VALIDATE | `IMPORT_VALIDATE` | ✅ | — |
| | LOAD | `IMPORT_LOAD` | ✅ | — |
| | FEEDBACK | `IMPORT_FEEDBACK` | ✅ | — |
| **EXPORT** | PREPARE | `EXPORT_PREPARE` | ✅ | — |
| | GENERATE | `EXPORT_GENERATE` | ✅ | — |
| | STORE | `EXPORT_STORE` | ✅ | — |
| | REGISTER | `EXPORT_REGISTER` | ✅ | — |
| | COMPLETE | `EXPORT_COMPLETE` | ✅ | — |
| **DISPATCH** | PREPARE | `DISPATCH_PREPARE` | ✅ | ✅ (3 轮) |
| | DISPATCH | `DISPATCH_DISPATCH` | ✅ | ✅ FAILED ×3 |
| | ACK | `DISPATCH_ACK` | ✅ | — |
| | RETRY | `DISPATCH_RETRY` | — | ✅ FAILED ×3 |
| | COMPENSATE | `DISPATCH_COMPENSATE` | — | ✅ SUCCESS ×3 |
| | COMPLETE | `DISPATCH_COMPLETE` | ✅ | — |

> Happy path 用 `tc/TC_IMPORT_RISK_SCORE`、`tc/TC_EXPORT_RISK_ALERT`、`tc/TC_DISPATCH_REVIEW`（→ `tc_local_archive`）验证。
> 异常 path 新增坏 channel `tc/tc_broken_local`（endpoint=`/dev/null/cannot-mkdir-here`），结合 `retry_policy=FIXED, retry_max_count=2`，DISPATCH 在 3 轮全失败后落 `dead_letter_task`。

---

## 2. 数据 / 配置准备清单

复跑前确认四件事，否则会撞已知坑：

### 2.1 biz 业务表灌到正确的库

worker-import / worker-export 的 `business` 数据源指向 `batch_business`（不是 `batch_platform.biz`）。
DDL 文件 `scripts/db/business/create_biz_tables.sql` 默认在当前数据库执行；务必显式 `-d batch_business`：

```bash
PGPASSWORD=batch_pass_123 psql -h localhost -p 15432 -U batch_user \
  -d batch_business -f scripts/db/business/create_biz_tables.sql
```

EXPORT 测试需要 `biz.risk_alert` 有 tc 数据（5 条以上），DISPATCH 不需要业务数据但需要前置 file_record。

### 2.2 EXPORT 的 templateCode 通过 `default_params` 注入

worker EXPORT 必须从 `task_payload.templateCode` 加载模板才能拿到 `query_param_schema.sqlTemplateExport` / `default_query_sql`。
若 `job_definition.default_params` 没配 `templateCode`，且 launch 时也不带，EXPORT 会立即报 `export_data_ref is required`。

```sql
UPDATE batch.job_definition
SET default_params = jsonb_build_object('templateCode','EXP-RISK-ALERT-JSON'),
    updated_at = now()
WHERE tenant_id='tc' AND job_code='TC_EXPORT_RISK_ALERT';
```

**改完必须重启 orchestrator**（`OrchestratorConfigCacheService` 缓存了 job_definition）。

### 2.3 worker 进程别长时间空闲

local 环境下 worker 进程在 macOS 上闲置数小时会被系统杀掉（验证过两次）。每次场景跑前先：

```bash
jps -l | grep worker          # 确认 3 个 worker.jar 都在
./scripts/local/restart.sh worker-import worker-export worker-dispatch  # 必要时拉起
```

### 2.4 异常路径需要 retry_max_count ≥ 1

`DISPATCH_RETRY` 是 partition retry 阶段才跑的 step。job_definition 的 retry_policy / retry_max_count 决定是否会进入 RETRY step：

```sql
UPDATE batch.job_definition
SET retry_policy='FIXED', retry_max_count=2
WHERE tenant_id='tc' AND job_code='TC_DISPATCH_REVIEW';
```

`retry_max_count=0` 时一次失败直接 DL，看不到 RETRY 调用。

---

## 3. Launch Payload 模板（curl）

通用 header：`-H "Content-Type: application/json" -H "Idempotency-Key: <uniq>"`，POST 到 `http://localhost:18081/api/triggers/launch`。

### 3.1 EXPORT

依赖 `default_params.templateCode` 已注入，无需额外字段。

```bash
curl -sS -X POST http://localhost:18081/api/triggers/launch \
  -H "Content-Type: application/json" -H "Idempotency-Key: $(date +%s)" \
  -d '{"tenantId":"tc","jobCode":"TC_EXPORT_RISK_ALERT","bizDate":"2026-04-25","triggerType":"MANUAL"}'
```

成功时产物：`file_record` 一行 `file_status=GENERATED`，`metadata_json.recordCount` = 真实查询行数。

### 3.2 IMPORT（带内嵌 content seed）

```bash
SEED='[{"entityId":"ENT-R-001","entityType":"ENTERPRISE","scoreValue":42.5,"scoreBand":"LOW","scoreDate":"2026-04-25","modelVersion":"v1"}]'
curl -sS -X POST http://localhost:18081/api/triggers/launch \
  -H "Content-Type: application/json" -H "Idempotency-Key: $(date +%s)" \
  -d "$(jq -n --arg c "$SEED" '{
    tenantId:"tc", jobCode:"TC_IMPORT_RISK_SCORE", bizDate:"2026-04-25", triggerType:"MANUAL",
    params:{
      templateCode:"IMP-RISK-SCORE-JSON",
      content:$c,
      fileFormatType:"JSON",
      fileCode:"TC-RISK-SCORE-20260425",
      originalFileName:"tc-risk-score.json",
      charset:"UTF-8"
    }
  }')"
```

成功时产物：`file_record.file_status=LOADED` + `parsedCount/validatedCount/loadedCount` 都 = seed 行数；`biz.risk_score` 真实新增行（`(tenant_id, entity_id, score_date)` 唯一约束，重复跑会 upsert）。

### 3.3 DISPATCH happy path

需要前置 `fileId`（来自 EXPORT 产出）+ `channelCode`：

```bash
curl -sS -X POST http://localhost:18081/api/triggers/launch \
  -H "Content-Type: application/json" -H "Idempotency-Key: $(date +%s)" \
  -d '{
    "tenantId":"tc","jobCode":"TC_DISPATCH_REVIEW","bizDate":"2026-04-25","triggerType":"MANUAL",
    "params":{"fileId":1065,"channelCode":"tc_local_archive","templateCode":"EXP-RISK-ALERT-JSON"}
  }'
```

成功时产物：`file_record.file_status: GENERATED → DISPATCHED`；`file_dispatch_record` 新一行 `dispatch_status=ACKED`；本地 LOCAL channel 落盘文件出现在 `target_endpoint` 目录下。

### 3.4 DISPATCH 异常路径（触发 RETRY + COMPENSATE）

#### a. 一次性配置（坏 channel + 重试策略）

```sql
INSERT INTO batch.file_channel_config
  (tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type, config_json, receipt_policy, timeout_seconds, enabled, created_at, updated_at)
VALUES
  ('tc', 'tc_broken_local', 'TC Broken Local (test retry/compensate)', 'LOCAL',
   '/dev/null/cannot-mkdir-here', 'NONE',
   '{"mkdirs": true, "targetDir": "/dev/null/cannot-mkdir-here"}'::jsonb,
   'NONE', 5, true, now(), now())
ON CONFLICT (tenant_id, channel_code) DO NOTHING;

UPDATE batch.job_definition
SET retry_policy='FIXED', retry_max_count=2
WHERE tenant_id='tc' AND job_code='TC_DISPATCH_REVIEW';
```

#### b. Launch（fileId 复用 happy path 的产出即可）

```bash
curl -sS -X POST http://localhost:18081/api/triggers/launch \
  -H "Content-Type: application/json" -H "Idempotency-Key: $(date +%s)" \
  -d '{
    "tenantId":"tc","jobCode":"TC_DISPATCH_REVIEW","bizDate":"2026-04-25","triggerType":"MANUAL",
    "params":{"fileId":1065,"channelCode":"tc_broken_local","templateCode":"EXP-RISK-ALERT-JSON"}
  }'
```

预期路径（`pipeline_step_run` 12 行）：

```
ROUND 1:  PREPARE✓ → DISPATCH✗(Not a directory) → RETRY✗ → COMPENSATE✓
ROUND 2:  PREPARE✓ → DISPATCH✗(channel health backoff) → RETRY✗ → COMPENSATE✓
ROUND 3:  PREPARE✓ → DISPATCH✗ → RETRY✗ → COMPENSATE✓
→ partition FAILED, retry_count=2
→ dead_letter_task: DISPATCH_SEND_FAILED
```

第 2 / 3 轮的 `DISPATCH_DISPATCH` 阶段错误从"Not a directory"变成"dispatch blocked by channel health backoff"，是 `DispatchChannelHealthService` 的熔断机制生效（首次失败后把 channel 标不健康，后续短路）。

---

## 4. 验证 SQL 速查

```sql
-- 实例 + 任务终态
SELECT instance_status, finished_at - started_at AS dur
FROM batch.job_instance WHERE instance_no = '<your-instance-no>';

-- 全 stage 跑过的步骤（核心证据）
SELECT psr.step_code, psr.stage_code, psr.step_status, psr.duration_ms,
       left(psr.error_message, 80) AS err
FROM batch.pipeline_step_run psr
JOIN batch.pipeline_instance pi ON pi.id = psr.pipeline_instance_id
WHERE pi.related_job_instance_id = (SELECT id FROM batch.job_instance WHERE instance_no = '<your-instance-no>')
ORDER BY psr.id;

-- IMPORT 落库验证
SELECT count(*) FROM biz.risk_score WHERE tenant_id='tc';   -- 在 batch_business 库执行

-- EXPORT 文件验证
SELECT id, file_name, file_status, metadata_json::jsonb->'recordCount' AS rec
FROM batch.file_record WHERE trace_id = '<launch 返回的 traceId>';

-- DISPATCH 落盘验证
SELECT id, file_id, channel_code, dispatch_status FROM batch.file_dispatch_record WHERE file_id = <fileId>;
ls -la /tmp/batch/tc-risk-alert/                              -- LOCAL channel target_endpoint
```

---

## 5. 已知遗留与限制

- **文件名占位符未替换**：EXPORT 产出的 `file_name` 含 `{bizDate}` 字面量，没有真做插值。重复跑 EXPORT 会撞 `(tenant_id, checksum_value, storage_path)` 唯一键。临时方案：每次重跑前先清掉旧 file_record（连带 `file_audit_log` / `pipeline_instance.file_id`）。根治需要 `naming_rule` 字段做模板插值。
- **worker 进程闲置约 8 小时会被系统回收**（macOS 本地 dev 环境特性，生产 supervisor 兜底）。每次连续跑前先 `jps -l | grep worker` 确认存活。
- **orchestrator 缓存 job_definition**：改 `default_params` / `retry_policy` 后必须重启 orchestrator 才生效。`OrchestratorConfigCacheService` 没有自动失效机制。
- **EXPORT/IMPORT 模板补齐**：本验证只覆盖 `tc/EXP-RISK-ALERT-JSON` 和 `tc/IMP-RISK-SCORE-JSON`。`default-tenant` 的 `exp_*` 系列模板 `query_param_schema` 大多缺 `sql_template_export` / `jdbc_mapped_export` 配置，跑起来一律报 `export_data_ref is required`，等后续按 risk_alert 模板的格式补齐。

---

## 6. 过程中修复的 bug 链（commit hash 速查）

| Commit | 问题 | 修复 |
|---|---|---|
| `e360f5c9` | console 缺按实例查 partition 的接口 | 加 `GET /api/console/queries/partitions` |
| `9daaacce` | 6 段长期日志噪声（cron / Redis ShedLock / 状态机 / NAS symlink 等） | 一锅端 |
| `64c7910e` | shutdown 期 RedisShedLockProvider 抛 `IllegalStateException` | catch 扩展 + OutboxPollScheduler 前置 isDraining 短路 |
| `6ba5383f` | 孤儿 job_definition / stale CREATED 实例 | 通用清理脚本 `cleanup-orphan-general-job.sql` |
| `28017d49` | TriggerDefinitionMapper 用 `#{arg0}` 但 Spring `-parameters` 启用 | 改 `#{tenantId}/#{jobCode}` |
| `d52933a1` | Quartz 误把 Linux 5 字段 cron 当 6 字段解析 | TriggerSchedulerFacade 加字段数硬校验 + TriggerReconciler 加 schedule drift detection |
| `3dbb6d22` | workflow JOB 节点不合并 node_params；sourcePayload 跨节点继承污染 | dispatchJobNode → buildChildLaunchRequest 合并 + 过滤 workflow 内部字段 |
| `46e2c256` | `ensurePipelineDefinition` 跨 worker 错位时累积 step | 已存在 pipeline 不再追加 default steps |

---

## 7. Worker × Stage 真实运行号速查（本验证产出）

| Job | instance_no | trace_id | 关键产物 |
|---|---|---|---|
| TC_EXPORT_RISK_ALERT (happy) | `inst-20260425T012919.763891Z-62e37c46` | `97d013c8...` | `file_record` 1065，`recordCount=5` |
| TC_IMPORT_RISK_SCORE | `inst-20260425T013324.276521Z-a882f263` | `13cd54d6...` | `file_record` 1066 LOADED；`biz.risk_score` +5 行 |
| TC_DISPATCH_REVIEW (happy) | `inst-20260425T013402.470926Z-750fe232` | `9337952e...` | `file_dispatch_record` 13 ACKED；`/tmp/batch/tc-risk-alert/` 落盘 |
| TC_DISPATCH_REVIEW (broken) | `inst-20260425T014600.863264Z-7a687191` | `897cd6dc...` | `pipeline_step_run` 12 行（4 stage × 3 round）；`dead_letter_task` 355 |
