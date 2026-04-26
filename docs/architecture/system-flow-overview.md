# 系统总流程 — 一眼看懂 file-batch-system 怎么运转

> 面向第一次接触本项目的人，**端到端业务视角**说清楚：触发 → 调度 → worker 执行 → 落地。
> 与 [`runtime-module-communication.md`](./runtime-module-communication.md) 互补：那份偏"模块间协议"，本份偏"一条任务的生命周期"。

---

## 1. 一张图看完整链路

> **图例**：
> - 线**类型**：粗实线 `══>` = 主数据流 / 写入 / publish；细虚线 `┄┄>` = 读取 / 上报 / 控制信号。
> - 线**颜色**（按协议分类）：🔵 蓝 = HTTP/REST 同步调用；🟢 绿 = SQL/JDBC 写；🟣 紫 = SQL/JDBC 读；🟠 橙 = Kafka 异步消息；🟡 黄 = Redis（cache / Streams / pub-sub）；🔴 红 = 外部副作用（MinIO PUT / SFTP 投递 / OpenAI）；⚫ 灰 = 控制信号 / 监控 / 心跳。

```mermaid
%%{init: {'flowchart': {'curve': 'linear', 'nodeSpacing': 50, 'rankSpacing': 70, 'htmlLabels': true}, 'themeVariables': {'fontSize': '13px'}}}%%
flowchart LR
  USER([用户 / 前端]):::user
  CONSOLE["batch-console-api<br/>(BFF · 鉴权/限流/审计)"]:::svc

  subgraph TRIG ["触发层 · batch-trigger（Quartz → Wheel 双轨过渡）"]
    direction TB
    WHEEL[("trigger_runtime_state<br/>trigger_misfire_pending<br/>(wheel.enabled=true)")]:::store
    QZ[(Quartz<br/>QRTZ_*<br/>legacy)]:::store
    WS["HashedWheelTriggerScheduler<br/>+ WheelTriggerReconciler<br/>+ MisfirePendingExpireScheduler<br/>+ CatchUpThrottle"]:::svc
    TR["TriggerSchedulerFacade<br/>+ TriggerReconciler<br/>(legacy)"]:::svc
    FLAG{{"QuartzPauseWhenWheelEnabledCustomizer<br/>switch on batch.trigger.wheel.enabled"}}:::flag
  end

  subgraph SCH ["调度层 · batch-orchestrator"]
    direction TB
    LS[LaunchService]:::svc
    PA[PartitionLifecycleService]:::svc
    SEL[DefaultWorkerSelector]:::svc
    OUT[OutboxPollScheduler]:::svc
  end

  PDB[("batch_platform<br/>job_* / file_* / workflow_*<br/>outbox_event / worker_registry")]:::store
  RDS[("Redis<br/>ShedLock / config-cache (pub/sub)<br/>shard-assignment / quota Lua")]:::store

  K[("Kafka<br/>batch.task.dispatch.*")]:::store

  subgraph WORKERS ["执行层 · 三类 Worker"]
    direction TB
    WI["worker-import<br/>RECEIVE → ... → FEEDBACK"]:::worker
    WE["worker-export<br/>PREPARE → ... → COMPLETE"]:::worker
    WD["worker-dispatch<br/>PREPARE → ... → COMPLETE"]:::worker
  end

  subgraph DATA ["数据落地"]
    direction TB
    BIZ[("batch_business<br/>biz.*")]:::store
    M[("MinIO<br/>batch-dev")]:::store
  end

  FS[("外部 target<br/>LOCAL / SFTP / NAS<br/>OSS / API")]:::extern

  %% ─── 触发链路（HTTP 入站 + 时序触发；Quartz 与 Wheel 二选一） ──
  USER    ==>|"POST /api/console/triggers/launch"| CONSOLE
  CONSOLE ==>|"HTTP forward (MANUAL)"| TR
  CONSOLE ==>|"HTTP forward (MANUAL)"| WS
  QZ      ==>|"cron fire (SCHEDULED · legacy)"| TR
  WHEEL   ==>|"tick fire (SCHEDULED · 默认/新)"| WS
  FLAG    -. "wheel.enabled=true → 暂停 Quartz" .-> QZ
  TR      ==>|"HTTP /internal/launch"| LS
  WS      ==>|"HTTP /internal/launch"| LS

  %% ─── 调度写库（JDBC INSERT） ─────────────────────
  LS ==>|"INSERT job_instance<br/>+ partition + outbox_event<br/>(同一 tx)"| PDB

  %% ─── orchestrator 内部读 / 写 PDB（控制） ────────
  PA  -. "write partition status (JDBC)" .-> PDB
  SEL -. "read worker_registry<br/>+ resource_queue (JDBC)" .-> PDB
  OUT -. "poll outbox_event (JDBC)" .-> PDB

  %% ─── Redis 辅助：ShedLock + config cache + shard-assignment ──
  SCH -. "ShedLock + config cache + quota Lua" .-> RDS

  %% ─── outbox → Kafka → workers（异步消息流） ──────
  OUT ==>|"publish task"| K
  K ==>|"consume (import)"| WI
  K ==>|"consume (export)"| WE
  K ==>|"consume (dispatch)"| WD

  %% ─── worker 上报回 LS（控制信号 HTTP） ───────────
  WI -. "claim / heartbeat / report (HTTP)" .-> LS
  WE -. "claim / heartbeat / report (HTTP)" .-> LS
  WD -. "claim / heartbeat / report (HTTP)" .-> LS
  LS ==>|"UPDATE job_instance<br/>+ partition (状态机, JDBC)"| PDB

  %% ─── worker 数据落地（业务写 / 外部副作用） ─────
  WI ==>|"INSERT biz.* (JDBC)"| BIZ
  WE -. "SELECT biz.* (JDBC)" .-> BIZ
  WE ==>|"PUT object"| M
  WD -. "GET object" .-> M
  WD ==>|"deliver (cp / scp / POST)"| FS

  %% ─── 边按协议着色（顺序与上面声明一致；linkStyle index 从 0 起） ──
  %%   0..7  HTTP / 触发层入站 (蓝色)
  linkStyle 0,1,2,3,4 stroke:#1565c0,stroke-width:2.5px
  %%   5     feature flag 控制（虚线灰）
  linkStyle 5 stroke:#616161,stroke-width:1.5px,stroke-dasharray:4 3
  %%   6,7   trigger → orchestrator（HTTP 蓝）
  linkStyle 6,7 stroke:#1565c0,stroke-width:2.5px
  %%   8     LS → PDB 写（绿）
  linkStyle 8 stroke:#2e7d32,stroke-width:2.5px
  %%   9..11 PA/SEL/OUT 读 PDB（紫虚）
  linkStyle 9,10,11 stroke:#7b1fa2,stroke-width:1.5px,stroke-dasharray:4 3
  %%   12    SCH ↔ Redis（黄虚）
  linkStyle 12 stroke:#f9a825,stroke-width:1.5px,stroke-dasharray:4 3
  %%   13..16 Kafka 链（橙）
  linkStyle 13,14,15,16 stroke:#ef6c00,stroke-width:2.5px
  %%   17..19 worker → LS 上报（HTTP 蓝虚 = 控制信号）
  linkStyle 17,18,19 stroke:#1565c0,stroke-width:1.5px,stroke-dasharray:4 3
  %%   20    LS → PDB 状态机更新（绿）
  linkStyle 20 stroke:#2e7d32,stroke-width:2.5px
  %%   21    worker-import → biz 写（绿）
  linkStyle 21 stroke:#2e7d32,stroke-width:2.5px
  %%   22    worker-export 读 biz（紫虚）
  linkStyle 22 stroke:#7b1fa2,stroke-width:1.5px,stroke-dasharray:4 3
  %%   23    worker-export → MinIO（外部红）
  linkStyle 23 stroke:#c62828,stroke-width:2.5px
  %%   24    worker-dispatch 读 MinIO（紫虚）
  linkStyle 24 stroke:#7b1fa2,stroke-width:1.5px,stroke-dasharray:4 3
  %%   25    worker-dispatch → 外部 target（红）
  linkStyle 25 stroke:#c62828,stroke-width:2.5px

  classDef user    fill:#e3f2fd,stroke:#1565c0,stroke-width:2px,color:#0d47a1;
  classDef svc     fill:#e8f5e9,stroke:#2e7d32,stroke-width:1.5px,color:#1b5e20;
  classDef worker  fill:#fff3e0,stroke:#ef6c00,stroke-width:1.5px,color:#e65100;
  classDef store   fill:#f3e5f5,stroke:#6a1b9a,stroke-width:1.5px,color:#4a148c;
  classDef extern  fill:#fce4ec,stroke:#ad1457,stroke-width:1.5px,color:#880e4f;
  classDef flag    fill:#fffde7,stroke:#f9a825,stroke-width:1.5px,color:#f57f17;
```

### 一句话叙事

1. **触发**：定时（`SCHEDULED` — 默认走 HashedWheel，旧 Quartz 路径仍可通过 `batch.trigger.wheel.enabled=false` 切回）或前端 `POST /api/triggers/launch`（`MANUAL`）→ trigger 写 `trigger_request` → HTTP 调 orchestrator。
2. **调度**：orchestrator `LaunchService` 写入 `job_instance` + `job_partition` + `outbox_event`（同一事务）。
3. **派发**：`OutboxPollScheduler` 把 outbox 事件发到 Kafka `batch.task.dispatch.{import|export|dispatch}` topic。
4. **执行**：对应类型 worker 消费 task → claim partition → 跑 pipeline 各 stage → 通过 HTTP 上报状态 → orchestrator 推进状态机。
5. **落地**：IMPORT 写 `batch_business.biz.*` 表；EXPORT 把生成的文件 PUT 到 MinIO 并登记 `file_record`；DISPATCH 把 `file_record` 派到外部通道（LOCAL / SFTP / NAS / OSS / API）。

---

## 1.5 运营视角 — console-api 的四个支线

主图聚焦「一条任务的生命周期」，console-api（BFF）只在主图体现「人工 launch」这一跳。
以下补充其余三条职责：配置管理、查询 / 报表、实时监控。

```mermaid
%%{init: {'flowchart': {'curve': 'linear', 'nodeSpacing': 50, 'rankSpacing': 60, 'htmlLabels': true}, 'themeVariables': {'fontSize': '13px'}}}%%
flowchart LR
  USER([用户 / 前端]):::user

  subgraph BFF ["batch-console-api"]
    direction TB
    AUTH["AuthN/Z + RateLimit<br/>+ Audit + Idempotent"]:::svc
    CFG[Config CRUD]:::svc
    QRY["Query / Report<br/>(@Transactional readOnly)"]:::svc
    SSE["RealtimeEventHub<br/>(SSE)"]:::svc
    AI["AI Assistant<br/>(OpenAI proxy)"]:::svc
    LAUNCH["Manual Launch<br/>+ Compensate / Rerun"]:::svc
  end

  PRIM[("PG primary<br/>:15432<br/>(写)")]:::store
  REPL[("PG replica<br/>:15433<br/>readOnly 路由<br/>fail-open 退主")]:::store
  REDIS[("Redis Streams<br/>+ pub/sub config evict")]:::store
  TRG["batch-trigger<br/>(/internal/launch)"]:::svc
  ORCH["batch-orchestrator<br/>(/internal/compensate /<br/>config-cache evict)"]:::svc
  OPENAI[(OpenAI / 私有化代理)]:::extern

  USER ==> AUTH
  AUTH --> CFG
  AUTH --> QRY
  AUTH --> SSE
  AUTH --> AI
  AUTH --> LAUNCH

  CFG    ==>|"INSERT / UPDATE<br/>job_definition / workflow_definition<br/>file_channel / batch_window"| PRIM
  CFG    -.->|"afterCommit pub/sub<br/>evict orchestrator cache"| REDIS
  CFG    -.->|"DB 直改场景手动 evict<br/>POST /api/console/ops/cache/evict-*"| ORCH
  QRY    ==>|"SELECT 走 read replica"| REPL
  QRY    -.->|"replica 挂 → fail-open 退主"| PRIM
  SSE    -.->|"消费 stream<br/>+ 推 SSE 给前端"| REDIS
  AI     ==>|"chat / classify"| OPENAI
  LAUNCH ==>|"HTTP forward"| TRG

  %% ─── 边按协议着色（与主图同套色编码，下标从 0 起） ─────────
  %%   0     USER → AUTH（蓝 = HTTP 入站）
  linkStyle 0 stroke:#1565c0,stroke-width:2.5px
  %%   1..5  AUTH → 5 个 handler（灰细 = 内部路由）
  linkStyle 1,2,3,4,5 stroke:#616161,stroke-width:1.2px
  %%   6     CFG → PRIM 写（绿）
  linkStyle 6 stroke:#2e7d32,stroke-width:2.5px
  %%   7     CFG → REDIS pub/sub evict（黄虚 = 控制信号）
  linkStyle 7 stroke:#f9a825,stroke-width:1.5px,stroke-dasharray:4 3
  %%   8     CFG → ORCH 手动 evict（蓝虚 = HTTP 控制）
  linkStyle 8 stroke:#1565c0,stroke-width:1.5px,stroke-dasharray:4 3
  %%   9     QRY → REPL 读（紫）
  linkStyle 9 stroke:#7b1fa2,stroke-width:2.5px
  %%   10    QRY → PRIM fail-open 退主（紫虚 = 降级控制）
  linkStyle 10 stroke:#7b1fa2,stroke-width:1.5px,stroke-dasharray:4 3
  %%   11    SSE → REDIS Streams（黄虚 = Redis pub/sub）
  linkStyle 11 stroke:#f9a825,stroke-width:1.5px,stroke-dasharray:4 3
  %%   12    AI → OpenAI（红 = 外部副作用）
  linkStyle 12 stroke:#c62828,stroke-width:2.5px
  %%   13    LAUNCH → TRG（蓝 = HTTP 同步）
  linkStyle 13 stroke:#1565c0,stroke-width:2.5px

  classDef user    fill:#e3f2fd,stroke:#1565c0,stroke-width:2px,color:#0d47a1;
  classDef svc     fill:#e8f5e9,stroke:#2e7d32,stroke-width:1.5px,color:#1b5e20;
  classDef store   fill:#f3e5f5,stroke:#6a1b9a,stroke-width:1.5px,color:#4a148c;
  classDef extern  fill:#fce4ec,stroke:#ad1457,stroke-width:1.5px,color:#880e4f;
```

### 关键约束

- **写路径只走 primary**：CRUD 接口的 `@Transactional`（默认 readWrite）路由到 primary；查询接口类级 `@Transactional(readOnly = true)` 路由到 replica。`ReadReplicaRoutingDataSource` 在 replica 异常时 fail-open 退回 primary（per-instance quarantine，详见 `ReadReplicaProperties`）。
- **配置改动必须走 console**：直改 DB 不会触发 afterCommit 事件 → orchestrator Redis 缓存失效要等 5min TTL；运维场景请用 `POST /api/console/ops/cache/evict-*` 6 个端点手动 evict（详见 `ConsoleConfigCacheController`）。
- **SSE 客户端绑定到具体实例**：实例死亡 → SSE 断开 → 浏览器 reconnect 落到新实例 → 从 Redis Streams replay buffer 续推（见 `ConsoleRealtimeEventHub` + `ConsoleRealtimeReplayStore`）。
- **AI 走代理出网**：所有 prompt 经 `ConsoleAiPromptGuard` 过滤分类（PLATFORM / REJECTED_*），结果写 `console_ai_audit_log` 留痕。

---

## 2. 三类 Worker 的 Stage 流程

每类 worker 都按"固定顺序的 stage 链"跑一个 partition。stage 定义在 `pipeline_step_definition` 表里，与 `job_code` 关联。

### 2.1 IMPORT — 6 stage

```mermaid
flowchart LR
  R0([task_payload<br/>含 content + templateCode])
  R1[RECEIVE<br/>读取 rawPayload<br/>登记 file_record]
  R2[PREPROCESS<br/>解码/解密/归一化<br/>load templateConfig]
  R3["PARSE<br/>JSON/CSV/XML/<br/>FixedWidth → NDJSON"]
  R4[VALIDATE<br/>field_mappings.required<br/>+ validation_rule_set]
  R5[LOAD<br/>jdbc_mapped_import<br/>upsert biz 表]
  R6[FEEDBACK<br/>写 file_record.metadata<br/>parsed/validated/loaded]
  END([file_status=LOADED])

  R0 --> R1 --> R2 --> R3 --> R4 --> R5 --> R6 --> END
```

| Stage | 关键动作 | 输入 | 输出 |
|---|---|---|---|
| RECEIVE | 创建 `file_record`（`file_status=RECEIVED`），把 `rawPayload` 入库 | `task_payload.payload`(JSON 字符串) | `attributes.fileId` |
| PREPROCESS | 解码/解压/normalize 原始字节；从 `template_code` 加载 `template_config` 入 `attributes` | rawBytes | `normalizedPayload` + templateConfig |
| PARSE | 按 `file_format_type` 选 parser，把字节流变成 NDJSON 文件 | normalizedPayload | `parsedRecordsPath`（temp 文件）|
| VALIDATE | 按 `field_mappings.required=true` 派生必填校验 + 显式 `validation_rule_set` | NDJSON | `validatedRecordsPath` + 跳过/失败计数 |
| LOAD | 用 `jdbc_mapped_import` 配置批量 upsert 到 `biz.{table}` | NDJSON | `loadedCount`，`biz.*` 真实写入 |
| FEEDBACK | 把 parsed/validated/loaded count 写回 `file_record.metadata_json` | counts | `file_status=LOADED` |

### 2.2 EXPORT — 5 stage

```mermaid
flowchart LR
  R0([task_payload<br/>含 templateCode])
  R1[PREPARE<br/>load templateConfig<br/>resolve fileName/path]
  R2[GENERATE<br/>跑 default_query_sql<br/>SELECT 结果]
  R3[STORE<br/>序列化 JSON/CSV<br/>+ gzip 上传 MinIO]
  R4[REGISTER<br/>登记 file_record<br/>计算 checksum]
  R5[COMPLETE<br/>file_status=GENERATED]
  END([file_record id 可被 DISPATCH 引用])

  R0 --> R1 --> R2 --> R3 --> R4 --> R5 --> END
```

| Stage | 关键动作 | 输入 | 输出 |
|---|---|---|---|
| PREPARE | 加载 `template_config`；`resolveFileName` 拼出 outbound 路径 | templateCode | `attributes.{fileName, objectName, exportSnapshot}` |
| GENERATE | 用 `query_param_schema.sqlTemplateExport`（或 jdbcMappedExport）跑 SQL，分页 keyset cursor | template_config + tenantId/batchNo | rows in temp file |
| STORE | gzip 序列化 + MinIO PUT | rows | `storage_path` |
| REGISTER | 计算 checksum，INSERT `file_record`（撞 `(tenant_id, checksum, storage_path)` 唯一键 → 报错）| storage_path + checksum | `file_record.id` |
| COMPLETE | 标 `file_status=GENERATED`，写 metadata（recordCount） | file_id | 终态 |

### 2.3 DISPATCH — 6 stage（含异常路径）

```mermaid
flowchart LR
  R0([task_payload<br/>含 fileId + channelCode])
  R1[PREPARE<br/>load file_record<br/>load channel_config]
  R2[DISPATCH_DISPATCH<br/>实际投递<br/>LOCAL/SFTP/NAS/OSS/API]
  R3[ACK<br/>等回执<br/>receipt_policy]
  R4[COMPLETE<br/>file_status=DISPATCHED]
  R5[RETRY<br/>backoff 退避]
  R6[COMPENSATE<br/>清理本轮残留]
  DL([dead_letter_task])

  R0 --> R1 --> R2
  R2 -- success --> R3 --> R4
  R2 -- fail --> R5
  R3 -- timeout/拒绝 --> R5
  R5 --> R6
  R6 -- "retry_count < max" --> R1
  R6 -- "retry_count = max" --> DL
```

| Stage | 关键动作 | 触发条件 |
|---|---|---|
| PREPARE | 校验 `file_record` 存在 + 加载 `channel_config` | 每轮第 1 步 |
| DISPATCH | 调对应 channel adapter 投递（LOCAL=cp / SFTP=scp / OSS=PUT / API=POST 等） | PREPARE 后 |
| ACK | 等回执（按 `receipt_policy`：NONE/SYNC/ASYNC） | DISPATCH 成功后 |
| COMPLETE | 写 `file_dispatch_record.dispatch_status=ACKED`，`file_record.file_status=DISPATCHED` | ACK 成功后（happy path 终点）|
| **RETRY** | 退避 + 准备重试；`DispatchChannelHealthService` 熔断不健康 channel | DISPATCH 或 ACK 失败 |
| **COMPENSATE** | 清理本轮已派发的残留（删 channel 上的临时文件 / 通知对端撤销） | 每轮 RETRY 后必跑 |

> 单 partition 失败完整路径示例（`retry_max_count=2`）：
> ```
> R1: PREPARE✓ → DISPATCH✗ → RETRY✗ → COMPENSATE✓
> R2: PREPARE✓ → DISPATCH✗ → RETRY✗ → COMPENSATE✓
> R3: PREPARE✓ → DISPATCH✗ → RETRY✗ → COMPENSATE✓ → DL
> ```

---

## 3. 配置如何驱动执行

执行链路完全由数据库表驱动，**没有任何 hard-coded 业务逻辑**。下图展示一个 task 从派发到 worker 拿到所需配置的全部路径：

```mermaid
flowchart LR
  TR[task_payload<br/>含 jobCode + templateCode<br/>+ fileId / content / channelCode] --> WORKER

  subgraph 配置层 [batch_platform 配置表]
    JD[job_definition<br/>worker_group<br/>retry_policy / retry_max_count<br/>default_params]
    PSD[pipeline_step_definition<br/>step_code 列表 6/5/6]
    FTC[file_template_config<br/>field_mappings<br/>validation_rule_set<br/>default_query_sql<br/>query_param_schema]
    FCC[file_channel_config<br/>channel_type<br/>target_endpoint<br/>config_json]
  end

  WORKER -- "loadPipeline(jobCode)" --> PSD
  WORKER -- "loadTemplate(templateCode)" --> FTC
  WORKER -- "loadChannel(channelCode)" --> FCC

  TR -. "trigger时<br/>orchestrator merge<br/>job_definition.default_params" .-> JD
  JD -. "提供 retry_policy<br/>给 RetryGovernanceService" .-> RG[RetryGovernanceService]
  RG -. "失败超过 max → DL" .-> DLT[(dead_letter_task)]
```

### 3.1 关键映射

| worker 行为 | 决定来源 |
|---|---|
| 一个 partition 跑哪条 stage 链 | `pipeline_step_definition.pipeline_definition_id`（按 jobCode 选定 pipeline） |
| 文件如何 parse / validate / load | `file_template_config`：`field_mappings`、`validation_rule_set`、`query_param_schema.jdbcMappedImport` |
| 文件如何 export | `file_template_config`：`default_query_sql` + `query_param_schema.sqlTemplateExport` |
| 文件投递到哪个目标 | `file_channel_config`：`channel_type` + `target_endpoint` + `config_json` |
| 失败如何重试 | `job_definition`：`retry_policy` (NONE/FIXED/EXPONENTIAL) + `retry_max_count` |
| 谁来跑这个 partition | `worker_registry` + `DefaultWorkerSelector`（match `worker_group` + `capability_tags`） |

### 3.2 三个表的最小 seed 例子

**EXPORT 模板**（让 `TC_EXPORT_RISK_ALERT` 能跑）：

```sql
-- file_template_config 一行
template_code      = 'EXP-RISK-ALERT-JSON'
template_type      = 'EXPORT'
file_format_type   = 'JSON'
default_query_sql  = 'SELECT id, alert_id, ... FROM biz.risk_alert WHERE tenant_id = :tenantId'
query_param_schema = {
  "export_data_ref": "sql_template_export",
  "sqlTemplateExport": { "table":"risk_alert", "schema":"biz",
                         "columns":["alert_id","entity_id",...] }
}

-- job_definition.default_params 注入 templateCode
UPDATE batch.job_definition
SET default_params = '{"templateCode":"EXP-RISK-ALERT-JSON"}'::jsonb
WHERE tenant_id='tc' AND job_code='TC_EXPORT_RISK_ALERT';
-- ⚠ 修改后必须重启 orchestrator（OrchestratorConfigCacheService 缓存 job_definition）
```

**IMPORT 模板**（`TC_IMPORT_RISK_SCORE`）：

```sql
template_code      = 'IMP-RISK-SCORE-JSON'
template_type      = 'IMPORT'
file_format_type   = 'JSON'
field_mappings     = [{"name":"entityId", "required":true, "targetColumn":"entity_id"}, ...]
query_param_schema = {
  "jdbcMappedImport": {
    "table":"risk_score", "schema":"biz",
    "columnMappings":[{"to":"entity_id","from":"entityId"}, ...],
    "conflictColumns":["tenant_id","entity_id","score_date"]   -- ON CONFLICT 触发 upsert
  }
}
```

**DISPATCH channel**（`tc_local_archive`）：

```sql
channel_code     = 'tc_local_archive'
channel_type     = 'LOCAL'
target_endpoint  = '/tmp/batch/tc-risk-alert'
config_json      = '{"mkdirs":true,"targetDir":"/tmp/batch/tc-risk-alert"}'
receipt_policy   = 'NONE'   -- 不等回执，写文件成功即 ACK
```

---

## 4. 跟着任务走一遍（三类全程）

下面三个例子都来自 2026-04-25 真实跑通的 instance，`pipeline_step_run` 的耗时是真实数据。

### 4.1 EXPORT 全程 — `TC_EXPORT_RISK_ALERT`

```mermaid
sequenceDiagram
    participant U as 用户
    participant T as trigger
    participant O as orchestrator
    participant K as Kafka
    participant E as worker-export
    participant DB as batch_platform
    participant BIZ as batch_business
    participant M as MinIO

    U->>T: POST /api/triggers/launch<br/>{tenantId:tc,<br/> jobCode:TC_EXPORT_RISK_ALERT}
    T->>O: HTTP /internal/launch
    O->>DB: INSERT job_instance(415)<br/>+ partition + outbox_event<br/>(同一 tx)
    O->>K: publish task 425
    K->>E: consume task 425
    E->>DB: claim partition (worker_code=export-node-1)
    Note over E: PREPARE: load EXP-RISK-ALERT-JSON template
    Note over E: GENERATE: SELECT * FROM biz.risk_alert WHERE tenant_id='tc'
    E->>BIZ: query → 5 rows
    Note over E: STORE: gzip + PUT
    E->>M: PUT outbound/.../risk_alerts__bizDate_.json.gz
    Note over E: REGISTER: INSERT file_record
    E->>DB: file_record(1065, GENERATED, 1073 bytes, recordCount=5)
    Note over E: COMPLETE
    E->>O: report SUCCESS
    O->>DB: UPDATE job_instance.status=SUCCESS
```

`pipeline_step_run` 记录：

```
EXPORT_PREPARE   SUCCESS  7ms
EXPORT_GENERATE  SUCCESS  27ms
EXPORT_STORE     SUCCESS  63ms
EXPORT_REGISTER  SUCCESS  14ms
EXPORT_COMPLETE  SUCCESS  11ms
```

### 4.2 IMPORT 全程 — `TC_IMPORT_RISK_SCORE`

注意 IMPORT 的输入不是 `biz` 表，而是 launch 时带在 `params.content` 里的 JSON 字符串（或 SFTP/上传到达的文件，由 ReceiveStep 转成 rawPayload）。

```mermaid
sequenceDiagram
    participant U as 用户
    participant T as trigger
    participant O as orchestrator
    participant K as Kafka
    participant I as worker-import
    participant DB as batch_platform
    participant BIZ as batch_business
    participant FS as 临时文件<br/>NDJSON

    U->>T: POST /api/triggers/launch<br/>{tenantId:tc, jobCode:TC_IMPORT_RISK_SCORE,<br/> params:{templateCode, content:"[...5 行 JSON...]"}}
    T->>O: HTTP /internal/launch
    O->>DB: INSERT job_instance(416) + partition + outbox<br/>(同一 tx)
    O->>K: publish task 426
    K->>I: consume task 426
    I->>DB: claim partition (worker_code=import-node-1)
    Note over I: RECEIVE: 写 file_record(1066, RECEIVED)
    I->>DB: file_record 1066 RECEIVED
    Note over I: PREPROCESS: 解码 content,<br/>load IMP-RISK-SCORE-JSON template
    I->>FS: 写临时 normalizedPayload
    Note over I: PARSE: JSON array → NDJSON 5 行
    I->>FS: 写 parsedRecordsPath (5 行)
    Note over I: VALIDATE: required 字段 + 业务规则
    I->>FS: 写 validatedRecordsPath
    Note over I: LOAD: jdbc upsert
    I->>BIZ: INSERT INTO biz.risk_score<br/>ON CONFLICT (tenant_id,entity_id,score_date)<br/>DO UPDATE × 5
    Note over I: FEEDBACK: 回写 metadata
    I->>DB: file_record 1066 LOADED<br/>metadata: parsed=5,validated=5,loaded=5
    I->>O: report SUCCESS
    O->>DB: UPDATE job_instance.status=SUCCESS
```

`pipeline_step_run` 记录：

```
IMPORT_RECEIVE     SUCCESS  60ms
IMPORT_PREPROCESS  SUCCESS  17ms
IMPORT_PARSE       SUCCESS  49ms
IMPORT_VALIDATE    SUCCESS  12ms
IMPORT_LOAD        SUCCESS  245ms   ← 5 行 upsert + 索引
IMPORT_FEEDBACK    SUCCESS  22ms
```

落地证据：`SELECT count(*) FROM biz.risk_score WHERE tenant_id='tc'` 真实新增 5 行（重复跑会 upsert，不会重复入库）。

### 4.3 DISPATCH 全程（happy path）— `TC_DISPATCH_REVIEW`

DISPATCH 的输入是已存在的 `file_record`（通常是上游 EXPORT 的产出），把它派发到一个外部通道。

```mermaid
sequenceDiagram
    participant U as 用户
    participant T as trigger
    participant O as orchestrator
    participant K as Kafka
    participant D as worker-dispatch
    participant DB as batch_platform
    participant M as MinIO
    participant LFS as LOCAL/SFTP/<br/>NAS/OSS/API

    U->>T: POST /api/triggers/launch<br/>{tenantId:tc, jobCode:TC_DISPATCH_REVIEW,<br/> params:{fileId:1065, channelCode:tc_local_archive}}
    T->>O: HTTP /internal/launch
    O->>DB: INSERT job_instance(417) + partition + outbox
    O->>K: publish task 427
    K->>D: consume task 427
    D->>DB: claim partition (worker_code=dispatch-node-1)
    Note over D: PREPARE: load file_record(1065) + channel_config(tc_local_archive)
    D->>DB: SELECT file_record / file_channel_config
    Note over D: DISPATCH: 从 MinIO 拉对象 + 写到 channel target
    D->>M: GET object outbound/...risk_alerts__bizDate_.json.gz
    D->>LFS: cp → /tmp/batch/tc-risk-alert/<br/>tc_local_archive-688467a5-...json
    Note over D: ACK: receipt_policy=NONE,<br/>本地写成功即 ACK
    Note over D: COMPLETE: 写 file_dispatch_record
    D->>DB: file_dispatch_record(13, ACKED)<br/>file_record 1065 → DISPATCHED
    D->>O: report SUCCESS
    O->>DB: UPDATE job_instance.status=SUCCESS
```

`pipeline_step_run` 记录：

```
DISPATCH_PREPARE   SUCCESS  30ms
DISPATCH_DISPATCH  SUCCESS  54ms
DISPATCH_ACK       SUCCESS  10ms
DISPATCH_COMPLETE  SUCCESS  8ms
```

> 异常路径（DISPATCH_RETRY + DISPATCH_COMPENSATE 怎么跑通）参考 [`../runbook/worker-stage-coverage.md` §3.4](../runbook/worker-stage-coverage.md)，那里有用坏 channel 触发 3 轮 retry 的真实 `pipeline_step_run` 12 行记录。

### 4.4 三个例子的对比

```mermaid
flowchart LR
  subgraph EXPORT
    EI([触发: 无外部输入]) --> EQ[查 biz 表] --> EF[生成文件]
    EF --> EM[(MinIO)]
    EF --> EFR[file_record GENERATED]
  end
  subgraph IMPORT
    II([触发: 外部 content / 上传文件]) --> IP[parse + validate]
    IP --> IL[upsert biz 表]
    IP --> IFR[file_record LOADED]
  end
  subgraph DISPATCH
    DI([触发: 已有 file_record]) --> DG[拉文件]
    DG --> DM[(MinIO)]
    DG --> DD[投递 channel]
    DD --> DT[(LOCAL/SFTP/<br/>OSS/API)]
    DD --> DFR[file_record DISPATCHED]
  end
```

| 类型 | 数据流向 | 输入 | 输出 |
|---|---|---|---|
| EXPORT | biz 表 → 文件 | 无（按 SQL 拉数据） | `file_record (GENERATED)` + MinIO 对象 |
| IMPORT | 文件 → biz 表 | `params.content` 或上传文件 | `file_record (LOADED)` + biz 表新增/更新 |
| DISPATCH | file_record → 外部通道 | `params.fileId` + `channelCode` | `file_record (DISPATCHED)` + `file_dispatch_record (ACKED)` + 外部 target 落盘 |

---

## 5. 状态机视角

每个 partition 在生命周期里穿过这些状态：

```mermaid
stateDiagram-v2
  [*] --> CREATED: 入库
  CREATED --> WAITING: 等待资源/quota
  WAITING --> READY: WaitingPartitionDispatchScheduler<br/>选到 worker
  CREATED --> READY: 直接派
  READY --> RUNNING: worker claim
  RUNNING --> SUCCESS: 全 stage SUCCESS
  RUNNING --> RETRYING: 某 stage 失败
  RETRYING --> READY: 重试派发
  RETRYING --> FAILED: 重试耗尽
  FAILED --> [*]: 进 dead_letter_task
  RUNNING --> CANCELLED: ops cancel
  WAITING --> CANCELLED: ops cancel
  SUCCESS --> [*]
  CANCELLED --> [*]
```

job_instance 的状态聚合：

```
INSTANCE_STATUS = CREATED → WAITING → READY → RUNNING →
                  SUCCESS / FAILED / PARTIAL_FAILED / CANCELLED / TERMINATED
```

`PARTIAL_FAILED` 表示一个 instance 下多个 partition，部分成功部分失败（仅多分区 IMPORT 才会出现）。

---

## 6. 为什么这样设计 — 几个关键不变量

1. **DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT**：所有任务派发必走这条主链，不允许 worker 直连 DB 改 `job_instance`。Orchestrator 是唯一状态主机。
2. **outbox_event 必须与状态写入同一事务**：保证"DB 看到的状态"和"Kafka 看到的事件"双向一致；任何一方失败都不丢消息。
3. **Worker 必须 CLAIM 才能执行**：partition 通过 `lease_expire_at` 实现 worker 抢占；worker 进程崩溃后租约到期，partition 自动可被其他 worker 接管。
4. **template / channel 配置都在 DB**：换个文件格式、换个投递通道，只需更新 `file_template_config` / `file_channel_config`，不用改代码或重启 worker。
5. **Pipeline 是配置不是代码**：`pipeline_step_definition` 决定 worker 跑哪些 stage，理论上可以为不同 jobCode 配置不同的 stage 子集。但实践中三类 worker 的 stage 链是固定模板（IMPORT 6 / EXPORT 5 / DISPATCH 6），改动会引发 step impl 缺失（参考 [worker-stage-coverage.md](../runbook/worker-stage-coverage.md)）。

---

## 7. Outbox 机制详解（DB → Kafka 的桥）

§6 第 1、2 条不变量都涉及 `outbox_event`。这一节讲清楚它怎么落地。

### 7.1 解决什么问题

orchestrator 派发任务时要做两件事：
1. 把 `job_instance` / `partition` 写进 PostgreSQL
2. 把"task dispatch"事件发到 Kafka 让 worker 消费

PostgreSQL + Kafka 没原生分布式事务。**先发 Kafka 后写 DB**：DB 失败 → worker 收到指向幽灵任务的事件；**先写 DB 后发 Kafka**：Kafka 失败 → 任务永远不会被 worker 看到。

**Outbox Pattern 的解法**：把"我要发的消息"也写到一张表 `outbox_event`，**和业务数据写在同一个 PG 事务里**；Kafka 投递解耦到独立 poller。

```mermaid
%%{init: {'flowchart': {'curve': 'linear', 'htmlLabels': true}}}%%
sequenceDiagram
    participant LS as LaunchService
    participant PDB as batch_platform
    participant OUT as OutboxPollScheduler
    participant K as Kafka

    Note over LS,PDB: 单事务: 业务表 + outbox_event 一起 commit
    LS->>PDB: BEGIN
    LS->>PDB: INSERT job_instance / partition
    LS->>PDB: INSERT outbox_event<br/>(publish_status='NEW')
    LS->>PDB: COMMIT
    Note over LS,PDB: 业务一致性已保证

    Note over OUT,K: 异步轮询，与业务事务解耦
    loop 每个 poll tick
        OUT->>PDB: SELECT outbox_event<br/>WHERE publish_status IN ('NEW','FAILED')<br/>LIMIT N
        OUT->>PDB: UPDATE publish_status='PUBLISHING'
        OUT->>K: producer.send(...)
        K-->>OUT: ack
        OUT->>PDB: UPDATE publish_status='PUBLISHED'<br/>(成功) 或 'FAILED' (失败)
    end
```

业务事务一旦 commit，事件就一定在 outbox 里；Kafka 暂挂、producer 重启、网络分区，业务不阻塞，最差只是延迟。

### 7.2 publish_status 状态机

```mermaid
stateDiagram-v2
  [*] --> NEW: LaunchService INSERT
  NEW --> PUBLISHING: poller claim
  PUBLISHING --> PUBLISHED: Kafka ack
  PUBLISHING --> FAILED: Kafka 拒绝/超时
  FAILED --> PUBLISHING: 下一轮 poll 重试
  FAILED --> GIVE_UP: 重试耗尽
  PUBLISHING --> FAILED: stale 超时重置<br/>(JVM 崩溃 / Kafka 卡死)
  PUBLISHED --> [*]
  GIVE_UP --> [*]
```

`PUBLISHING` 是中间态，表示"某 poll 节点已 claim 但还未 ack"。**stale PUBLISHING 重置**是关键容错：JVM 在 publish 中途崩溃时，事件会卡在 PUBLISHING 永远没人捡；poller 每轮开头扫一遍超过 `publishingTimeoutSeconds`（默认 60s）的 PUBLISHING 全部拨回 FAILED 让下轮接管。

### 7.3 自适应轮询间隔

不是固定 `@Scheduled(fixedDelay = 5s)`，而是**动态调速**：

```
本轮处理了事件（积压）→ 下轮立即 (minPollIntervalMillis,  默认 200ms)
本轮空跑（无事件）   → 间隔 ×= backoffMultiplier
                      退避到上限 pollIntervalMillis     (默认 5s)
```

业务繁忙时接近实时（200ms 一轮），系统空闲时降到 5s，**减少 DB 空查询压力**。实现上用 `ScheduledExecutorService` 自调度（不用 Spring `@Scheduled`），每轮自己决定下次间隔。

### 7.4 分布式互斥与分片（ShedLock + Sharding）

orchestrator 集群多实例时，不能让两个实例同时 poll 同一批 outbox_event（会重复发 Kafka）。用 ShedLock 加 Redis 分布式锁：

| 模式 | 锁 key | 行为 |
|---|---|---|
| 单实例 / `shardTotal=1` | `outbox_poll` | 集群中只有一个实例真正在 poll，其他抢不到锁直接跳过 |
| 多分片 / `shardTotal>1` | `outbox_poll_shard_0` / `_shard_1` / ... | 允许 N 个实例**并行**处理不同分片，吞吐放大 N 倍 |

锁参数：`lockAtMostFor=1min`（保险上限，避免 JVM 崩了一直握锁）；`lockAtLeastFor=3s`（最少持锁时间，避免高频抢锁雪崩）。

### 7.5 熔断与优雅下线

- **熔断（OutboxPublishCircuitBreaker）**：连续失败超阈值时整轮跳过 advance，避免 Kafka 雪崩时把 outbox 打成大面积 FAILED。三态：关闭 / 打开 / 半开。
- **优雅下线**：`gracefulShutdown.isDraining()=true` 时跳过本轮 + 跳过抢锁（防 Lettuce 已 STOPPED 还去 Redis 抢锁抛 `IllegalStateException`，详见 CLAUDE.md 2026-04-24）。

### 7.6 关键参数（`OutboxProperties`）

| 参数 | 默认 | 作用 |
|---|---|---|
| `min-poll-interval-millis` | 200 | 有事件时下轮间隔下限 |
| `poll-interval-millis` | 5000 | 无事件时退避间隔上限 |
| `backoff-multiplier` | 2.0 | 空闲时间隔放大系数 |
| `publishing-timeout-seconds` | 60 | PUBLISHING 超过此值判 stale 重置回 FAILED |
| `shard-total` / `shard-index` | 1 / 0 | 多实例分片配置 |
| `batch-size` | 100 | 每轮 SELECT 多少行 |

### 7.7 在 §1 总览图里它的位置

```
LS ==> PDB                         ← 业务事务写 outbox_event (NEW)
                                     (同事务保证一致性)
OUT -. poll outbox_event .-> PDB   ← poller 自己读 (虚线 = 主动 poll)
OUT ==> K (publish task)           ← 把 publishable 的发到 Kafka
```

Outbox 是**业务事务和 Kafka 投递之间的桥**——双方完全解耦，业务永远不会因为 Kafka 暂挂阻塞，Kafka 恢复后 poller 自动追回。

---

## 8. 进一步阅读

| 你想看 | 文档 |
|---|---|
| 一条任务的具体表读写 | [`runtime-module-communication.md`](./runtime-module-communication.md) |
| 模块边界与版本基线 | [`architecture-truth.md`](./architecture-truth.md) |
| 核心实体关系 | [`core-model.md`](./core-model.md) |
| Worker 插件如何注册 | [`worker-plugins.md`](./worker-plugins.md) |
| Kafka topic 命名 | [`kafka-topic-plan.md`](./kafka-topic-plan.md) |
| 真实端到端验证（含 RETRY/COMPENSATE） | [`../runbook/worker-stage-coverage.md`](../runbook/worker-stage-coverage.md) |
| 编码与字典约定 | [`/CLAUDE.md`](../../CLAUDE.md) |
