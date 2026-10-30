# 系统总流程 — 一眼看懂 file-batch-system 怎么运转

> 面向第一次接触本项目的人，**端到端业务视角**说清楚：触发 → 调度 → worker 执行 → 落地。
> 与 [`runtime-module-communication.md`](./runtime-module-communication.md) 互补：那份偏"模块间协议"，本份偏"一条任务的生命周期"。

---

## 1. 一张图看完整链路

定时、人工、文件到达与外部事件均进入显式 Workflow 目标。Quartz 是定时机会计算器，不能从框架注册表推断业务日已履行；各触发来源的占比需用实际运行数据衡量。

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart TB
  E["日程 / Console / 到达事件"] --> T["Trigger 接纳请求"]
  T --> TO[("请求 + Trigger Outbox")]
  TO --> TR["Trigger Relay"] --> K1["Kafka · launch"]
  K1 --> O["Orchestrator"]
  subgraph R["顶层运行与按需物化"]
    WR["WorkflowRun · 冻结图"] --> NR["WorkflowNodeRun"]
    NR --> C["START / END / GATEWAY / WAIT"]
    NR --> J["JOB → 独立 JobInstance"] --> PT["Partition / Task"]
  end
  O --> WR
  PT --> TX[("运行状态 + Task Outbox")]
  TX --> OR["Outbox Relay"] --> K2["Kafka · task dispatch"]
  K2 --> W["五类 Worker"]
  W -->|"HTTP CLAIM / REPORT"| O
  W --> B["业务数据 / 文件 / 外部服务"]
  O -. "按义务与终态证据结算" .-> BD["BatchDay"]
```

### 一句话叙事

1. Trigger 固定目标 Workflow、原 fire/event 身份及日期，在事务中写请求与触发 Outbox；Relay 经 Kafka 交给 Orchestrator。
2. Orchestrator 准备 WorkflowRun、START 节点和冻结图。到达 JOB 节点时，子请求才创建该节点的 JobInstance，控制节点不预建空作业。
3. 作业执行按配额、窗口、依赖及分区计划创建任务，状态与 Outbox 同事务；消息按五类 Worker 路由。
4. Worker 收消息后 CLAIM，执行前核验 invocation/冻结配置，按任务类型执行并 REPORT。文件 Pipeline 的阶段与版本来自系统目录。
5. Orchestrator 聚合作业、节点与 Workflow 终态，独立生成结果、通知和审计证据；BatchDay 结合义务、覆盖、在途工作与结果结算。

业务数据库与平台状态库分开。Worker 可经明确的数据源和租户隔离访问业务库及文件元数据，不能直接推进 Workflow/Job/Task 核心状态；SDK 不获得平台库写权限。

### 1.4 Trigger → Orchestrator 异步路径（ADR-010，固化无开关）

trigger → orchestrator 调度链走 outbox + Kafka 异步模式（2026-05-02 固化，原同步 HTTP 桥已删除）：

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
sequenceDiagram
  participant Caller as 日程 / Console
  participant T as Trigger
  participant DB as 平台数据库
  participant Relay as Trigger Relay
  participant K as Kafka
  participant O as Orchestrator
  Caller->>T: 来源身份 + Workflow 目标
  T->>DB: 同事务保存请求与 Outbox
  T-->>Caller: requestId / 接纳结果
  Relay->>DB: 认领待发布事件
  Relay->>K: LaunchEnvelope
  K-->>Relay: ACK
  Relay->>DB: 标记发布结果
  K->>O: 投递原请求
  O->>DB: 幂等准备 WorkflowRun
```

**收益**：trigger 重启不丢已 fire 的 launch（Quartz fire 后第一时间落 outbox，relay 重启续传）；orchestrator 短暂宕机不阻塞 trigger Quartz worker thread。

**消息载荷**：`LaunchEnvelope` JSON `{ launchRequest, dedupKey, sourceFireTime, envelopeVersion }`，headers 携带 `X-Trace-Id` / `X-Tenant-Id` / `X-Envelope-Version`。

---

## 1.5 运营视角 — console-api 的五个支线

主图聚焦「一条任务的生命周期」，console-api（BFF）只在主图体现「人工 launch」这一跳。
以下补充其余四条职责：配置管理、查询 / 报表、实时监控、补偿命令。

> **注意**：本节的图**只画 console-api 视角**，所有边都从 `USER` 起。这不代表系统是前台驱动 ——
> 定时任务由 Trigger 自动发起，人工/API/事件入口按各自契约接纳，实际占比取决于租户负载。本节只是补充运营 5 条支线，不要据此理解为"系统都靠人点"。

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart TB
  U["Console 前端"] --> C["Console API · 认证 / 租户 / 授权"]
  C --> CFG["配置用例"] --> DB[("平台 PostgreSQL")]
  C --> Q["运行查询"] --> DB
  C --> RT["SSE 事件桥"]
  RT <--> R["Redis Pub/Sub + 重放缓冲"]
  RT --> U
  C --> L["运行发起"] --> T["Trigger"]
  C --> OPS["审批 / 补偿 / 重放"] --> O["Orchestrator"]
  C --> AI["AI 只读工具 / 会话"]
  AI --> DB
  AI --> P["受控模型服务"]
  AI --> A["独立附件存储"]
```

### 关键约束

- **写路径只走 primary**：CRUD 接口的 `@Transactional`（默认 readWrite）路由到 primary；查询接口类级 `@Transactional(readOnly = true)` 路由到 replica。`ReadReplicaRoutingDataSource` 在 replica 异常时 fail-open 退回 primary（per-instance quarantine，详见 `ReadReplicaProperties`）。
- **配置改动必须走 console**：直改 DB 不会触发 afterCommit 事件 → orchestrator Redis 缓存失效要等 5min TTL；运维场景请用 `POST /api/console/ops/cache/evict-*` 6 个端点手动 evict（详见 `ConsoleConfigCacheController`）。
- **SSE 客户端绑定到具体实例**：实例死亡 → SSE 断开 → 浏览器 reconnect 落到新实例 → 从 Redis 重放缓冲续推（见 `ConsoleRealtimeEventHub` + `ConsoleRealtimeReplayStore`）。
- **AI 走代理出网**：所有 prompt 经 `ConsoleAiPromptGuard` 过滤分类（PLATFORM / REJECTED_*），结果写 `console_ai_audit_log` 留痕。
- **补偿命令写入数据库 + outbox 派发**：`POST .../compensate` 经 console → orchestrator `/internal/compensations` → INSERT `compensation_command` + 同事务 `outbox_event(eventType=COMPENSATE)`，由 OutboxPollScheduler 异步发到 Kafka，对应 worker 反向执行（DELETE biz / 删 MinIO 对象 / 通知外部对端撤销）。

---

## 1.6 观测栈 — Metrics / Traces / Logs 三件套

主图省略了观测链路，因为每个 service 节点都向 OTel Collector 上报，画进主图会变意大利面。
单独画一张图说明：所有进程怎么把可观测信号送出去 + 运营怎么从 Grafana 一站排障。

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart TB
  A["控制服务 + 五类 Worker"] -->|"Trace / Log"| OT["OpenTelemetry Collector"]
  OT --> T["Tempo · Trace"]
  OT --> L["Loki · Log"]
  OT -. "可选兼容后端" .-> J["Jaeger"]
  P["Prometheus"] -->|"抓取指标"| A
  P --> AM["Alertmanager"] --> N["通知渠道"]
  G["Grafana"] -->|"查询"| T
  G -->|"查询"| L
  G -->|"查询"| P
```

### 一站排障路径（典型）

1. Grafana **Dashboards** 看 metric outlier（`outbox_publish_latency_seconds.p99` 飙高）
2. 在 metric 图点 **exemplar 钻石** → 跳到 Tempo 那条 trace
3. Tempo **trace view** 找最长 span（`OutboxPublisher.publish`）
4. Tempo span 右上 **"Logs for this span"** → Loki 自动按 traceId+service 过滤展开 ±5min
5. 同时看 Tempo 自带 **service graph + span metrics**（`metrics_generator`），定位下游依赖问题

详见 [`docs/runbook/observability-stack.md`](../runbook/observability-stack.md)。

---

## 1.7 Workflow DAG 编排 — Worker 之上的另一层

单作业与组合图共用同一层DAG；图的层级不是可配置的子Workflow树。

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart TB
  D["WorkflowDefinition"] -->|"冻结"| R["WorkflowRun · 顶层运行"]
  R --> N["WorkflowNodeRun"]
  N --> C["控制节点 · 就地推进"]
  N -->|"JOB 到达"| J["独立 JobInstance"]
  J --> P["JobPartition"] --> T["JobTask"]
  J -. "文件类型" .-> PI["系统 Pipeline · 阶段观测"]
  T -->|"CLAIM / EXECUTE / REPORT"| W["Worker"]
  J -->|"终态与输出"| N
```

### 关键决策点

- 新图执行节点使用JOB；START/END/GATEWAY就地推进，WAIT持久化轮询。TASK虽在枚举中保留，新根没有旧Job父实例时明确拒绝；FILE_STEP及旧WORKFLOW作业已退役。
- 条件边和join只描述同一运行内控制关系；跨运行Job结果或整个Workflow成功是另外的持久化依赖，不能从DAG边隐式推导。
- 同一Job可被多个节点引用，节点各自有独立执行身份。当前不支持嵌套Workflow；START→END和WAIT跳过路径同样需要终态收敛。

## 1.8 死信旁路（DLQ）— Kafka 隔离 + DB 落盘 + console 重放

主图省略了 DLQ，因为是**异常旁路**，不在 happy-path 主链上。但生产环境必跑，独立画。

DLQ 在本系统里**走两条互补轨**：
- **Kafka 隔离轨**：worker 处理失败的"毒丸消息"立即转发到 `batch.task.dead-letter` topic 隔离，
  防止毒丸阻塞主派发队列（`DeadLetterPublisher`），运维侧用 Kafka 工具消费查看
- **DB 落盘轨**：orchestrator 把 retry 耗尽的失败任务 INSERT `dead_letter_task` 表，
  console 可查询 + 调 `POST /api/console/jobs/dead-letters/replay` 触发重放
  （`DefaultRetryGovernanceService`）

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart TB
  W["Worker"] -->|"无法解析 / 毒消息"| DP["DeadLetterPublisher"] --> KDL["Kafka DLQ"]
  W -->|"执行失败 REPORT"| O["Orchestrator"]
  O --> RS[("retry_schedule")]
  RS -->|"到期 / 允许重试"| O
  O -->|"耗尽或不可重试"| DL[("dead_letter_task")]
  C["Console 审批"] -->|"受控重放请求"| O
  DL -. "原始失败证据" .-> O
  O -->|"新运行 / 尝试 + 来源关联"| TX[("运行状态 + Outbox")]
  TX --> R["Outbox Relay"] --> K["Kafka · task dispatch"] --> W
```

### `dead_letter_task.replay_status` 状态机

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
stateDiagram-v2
  [*] --> NEW: 创建死信
  NEW --> REPLAYING: 审批并开始重放
  NEW --> GIVE_UP: 放弃
  REPLAYING --> SUCCESS: 重放成功
  REPLAYING --> FAILED: 重放失败
  FAILED --> NEW: 再次提交处理
  FAILED --> GIVE_UP: 放弃
  SUCCESS --> [*]
  GIVE_UP --> [*]
```

### 关键约束

- **两轨独立**：Kafka 隔离轨（`DeadLetterPublisher → batch.task.dead-letter`）与 DB 落盘轨（`dead_letter_task` 表）**互不依赖**。前者纯 Kafka 隔离避免阻塞，后者支持业务侧重放治理。
- **重放幂等**：`markReplaying` 用乐观锁（`replay_status='NEW' AND id=?`），避免并发 replay；重放写新 `outbox_event(eventType=REPLAY)`，复用主链 publish 路径，**不绕过状态机**。
- **GIVE_UP 是终态**：人工标记后不再 replay，需重置回 NEW 才能再走重放路径。
- **source_type 决定重放粒度**：可重放 task / partition / instance / pipeline_instance / file_dispatch / mq_message 6 类，由 `RetryGovernanceService` 路由分发。

---

## 1.9 弹性伸缩拓扑 — 应用副本与协调机制

Trigger 的 Quartz 集群锁协调日程触发，辅助调度使用 ShedLock；Orchestrator 依靠持久化状态控制与 Outbox 分片协作；Worker 依靠 Kafka consumer group 与分区租约扩展。副本上下限、HPA 或 KEDA 以各环境部署配置为准。

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart TB
  subgraph CONTROL["控制服务"]
    T["Trigger 副本"] --- Q["Quartz 数据库锁"]
    O["Orchestrator 副本"] --- SH["Outbox 分片 / 持久化 CAS"]
    C["Console API 副本"]
  end
  subgraph EXEC["执行服务"]
    W["IMPORT / EXPORT / PROCESS / DISPATCH / ATOMIC"]
    CG["Kafka consumer group + Partition lease"]
    W --- CG
  end
  P["Prometheus 指标"] --> H["HPA · 资源 / 自定义指标"]
  K["Kafka lag"] --> KE["KEDA · 事件指标"]
  H -. "按部署配置选择" .-> W
  KE -. "按部署配置选择" .-> W
  H -. "按服务配置" .-> CONTROL
```

### 关键约束（按"为什么这样扩"分类）

| 模块 | 扩缩特点 | 为什么 |
|---|---|---|
| **console-api** | 完全无状态，纯横向扩 | BFF 层，请求间无依赖；HPA 按 CPU + HTTP QPS |
| **trigger** | 横向扩靠 Quartz cluster lock 内部协调；辅助调度走 ShedLock JDBC | 同 cron 触发的多实例靠 `QRTZ_LOCKS` 排他抢占，应用层不感知 |
| **orchestrator** | 横向扩靠 Outbox 动态分片 + ShedLock；StatefulSet 部署 | 多实例并行处理不同 outbox shard；ShedLock 保证调度类任务全集群单实例执行 |
| **worker-***  | 横向扩靠 Kafka consumer group rebalance + lease | 实例数 ≤ Kafka partition 数；超过 = 多余实例空闲；KEDA 按 lag 自动扩缩 |

### 三个扩缩硬上限（红色虚线）

1. **PG `max_connections`**：所有模块总连接数上限。N 实例 × 单实例 pool ≤ PG max_connections（生产 500-1000，加 PgBouncer 可放大）
2. **Kafka partition 数**：限制 worker 并发上限。partition=10 → 最多 10 个 worker 实例消费同一 topic；超出空跑
3. **Redis 高可用**：orchestrator 的 ShedLock / 限流 / 配额 / SSE replay 全靠 Redis；单点 Redis 挂 ≈ orchestrator 协调失效（trigger 走 PG，不受影响）

### 详细文档

- 扩缩机制 + HPA 配置示例 → [`../runbook/autoscaling-strategy.md`](../runbook/autoscaling-strategy.md)
- HA 兼容性矩阵 + 缩容 4 个隐藏雷 + 部署前 checklist → [`../runbook/ha-elastic-scaling.md`](../runbook/ha-elastic-scaling.md)
- 容量规划、负载证据和准入边界 → [production-capacity-governance.md](../runbook/production-capacity-governance.md)

---

## 2. 四类文件 Worker 的 Stage 流程

IMPORT、EXPORT、PROCESS、DISPATCH 四类文件 Worker 按固定阶段契约执行；ATOMIC 使用独立任务执行器，不进入文件 Pipeline 阶段循环。阶段与版本由 `SystemPipelineCatalog` 和对应 Worker 实现固定；Job 保存类型化执行参数。DISPATCH 的 ACK、重试和补偿按结果分支推进，不代表每次执行都经过全部阶段。

### 2.1 IMPORT — 6 stage

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart LR
  A["RECEIVE"] --> B["PREPROCESS"] --> C["PARSE"] --> D["VALIDATE"] --> E["LOAD"] --> F["FEEDBACK"]
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
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart LR
  A["PREPARE"] --> B["GENERATE"] --> C["STORE"] --> D["REGISTER"] --> E["COMPLETE"]
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
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart TB
  P["PREPARE"] --> D["DISPATCH"] -->|"已发送"| A["ACK"] -->|"确认完成"| C["COMPLETE"]
  D -->|"明确可重试"| R["RETRY"]
  A -->|"请求重试"| R
  R -->|"重试成功"| A
  R -->|"失败 / 不满足重试条件"| CP["COMPENSATE · 终止本轮"]
  D -. "外部结果未知" .-> U["先核对外部结果 · 禁止盲目重投"]
  R -. "外部结果未知" .-> U
```

| Stage | 关键动作 | 触发条件 |
|---|---|---|
| PREPARE | 校验 `file_record` 存在 + 加载 `channel_config` | 每轮第 1 步 |
| DISPATCH | 调对应 channel adapter 投递（LOCAL=cp / SFTP=scp / OSS=PUT / API=POST 等） | PREPARE 后 |
| ACK | 等回执（按 `receipt_policy`：NONE/SYNC/ASYNC） | DISPATCH 成功后 |
| COMPLETE | 写 `file_dispatch_record.dispatch_status=ACKED`，`file_record.file_status=DISPATCHED` | ACK 成功后（happy path 终点）|
| **RETRY** | 明确允许时重新投递；成功转 ACK，失败按结果结束或转 COMPENSATE | DISPATCH 或 ACK 请求重试；未知外部结果先核对 |
| **COMPENSATE** | 标记投递补偿结果、文件失败并记录审计；不承诺撤销外部副作用 | 重试失败或不满足重试条件时的分支；本轮终点 |

> 以下示例展示 task 层重试产生的三次尝试（`retry_max_count=2`），不表示 COMPENSATE 在同一 Pipeline 内自动跳回 PREPARE：
> ```
> R1: PREPARE✓ → DISPATCH✗ → RETRY✗ → COMPENSATE✓
> R2: PREPARE✓ → DISPATCH✗ → RETRY✗ → COMPENSATE✓
> R3: PREPARE✓ → DISPATCH✗ → RETRY✗ → COMPENSATE✓ → DL
> ```

### 2.4 PROCESS — 5 stage（WAP+bookends）

PROCESS 解决"系统内部加工"（聚合 / 清洗 / 状态推进），**Write-Audit-Publish** 模式：先把计算结果写到 staging 隔离区，校验通过后再原子发布到 target，避免异常数据落到生产表。`COMPUTE` 由插件分派（`sqlTransformCompute` 配置驱动 / `ProcessComputePlugin` 业务自定义）。

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart LR
  A["PREPARE"] --> B["COMPUTE"] --> C["VALIDATE"] --> D["COMMIT"] --> E["FEEDBACK"]
  B -. "JSONB 模式" .-> S[("业务 staging")]
  D --> T[("业务 target")]
  C -->|"失败"| F["失败报告 / 保留核查证据"]
```

| Stage | 关键动作 | 输入 | 输出 |
|---|---|---|---|
| PREPARE | 校验 sqlTransformCompute spec / target 表存在性 / schema allowlist；生成 `batchKey`；删除本 batchKey 历史 staging | `step_params.sqlTransformCompute` + `payload.bizDate` | `attributes.batchKey` |
| COMPUTE | 默认 JSONB 模式用 `processBusinessDataSource` 跑 `sourceSql`，逐行 `jsonb_build_object` 写入 `batch.process_staging`（按 batchKey 隔离）；`stagingMode=DIRECT` 只登记快路径元信息。或走自定义 `ProcessComputePlugin.compute()` | sourceSql + 命名参数（`:tenantId / :bizDate / :highWaterMarkIn`） | staging 行数 / DIRECT 模式标记 |
| VALIDATE | JSONB 模式按 `validations[].checkSql` 跑用户校验（典型：`SELECT bool_and(...) AS pass, ... AS message`）；`DIRECT` 模式不支持 staging validations | staging 行 + checkSql | pass/fail（fail → 跳过 COMMIT） |
| COMMIT | JSONB 模式 `INSERT INTO target SELECT jsonb_populate_record(...) FROM staging WHERE batch_key=?` 一句原子发布；`DIRECT` 模式 `INSERT INTO target SELECT ... FROM (sourceSql) base` 直接发布；按 `writeMode` 走 INSERT / UPSERT / INSERT_IGNORE | staging 或 sourceSql | target 表写入 + `attributes.publishedCount` |
| FEEDBACK | JSONB 模式 `DELETE FROM staging WHERE batch_key=?` 清场；`DIRECT` 模式跳过 staging 清理；从插件 / 配置写 `highWaterMarkOut` 到 attributes（orchestrator 在 success 路径下回写 `job_instance`） | publishedCount + watermark column | `high_water_mark_out` |

> **失败语义差异（与 IMPORT/EXPORT/DISPATCH 对比）**：
> - VALIDATE 失败：staging **保留**（不清），target 不变 → forensics 友好；下次 PREPARE 同 batchKey 才会清
> - COMMIT 失败：staging 保留 + target 部分写（INSERT/UPSERT 是单语句原子，但跨语句 batch 失败可能留半成品），需业务在 target 上做幂等键防重
> - DISPATCH 有独立的 RETRY / COMPENSATE 阶段；PROCESS **没有**——整个 task 失败由 task 层 `retry_policy` 重跑，每次重跑生成新 batchKey 走完整 5 stage

> 详细 SQL 模板与运行回路见 [`../design/batch-classification-and-gaps.md`](../design/batch-classification-and-gaps.md) §4.5；ExecutionMode.INCREMENTAL 水位回路见 §7.8。

---

## 3. 配置如何驱动执行

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart TB
  WD["Workflow 定义"] --> WR["WorkflowRun · 冻结图"]
  JD["Job 定义"] --> CFG["有效执行配置"]
  TC["模板 / 通道 / Secret 版本"] --> CFG
  WR -->|"JOB 节点快照"| CFG
  CAT["SystemPipelineCatalog · 固定阶段"] -->|"四类文件 Job"| W["对应文件 Worker"]
  CFG --> W
  CFG --> A["ATOMIC Worker · 独立执行"]
```

### 3.1 关键映射

| 配置 | 生效边界 |
|---|---|
| Workflow 日程、时区、日历和图 | 顶层接纳与冻结；重复 fire 由持久身份区分，不能用当前时间重新计算旧运行日期 |
| Job 类型与执行参数 | JOB 节点的有效配置与执行策略；不再配置第二份顶层日程 |
| 模板、通道与密钥版本 | 在支持的实例/Replay路径捕获和校验；恢复复用冻结版本，撤销与安全准入仍可拒绝执行 |
| SystemPipelineCatalog | IMPORT/EXPORT/PROCESS/DISPATCH固定阶段；束类型映射到对应文件类型，GENERAL/ATOMIC不创建文件Pipeline |
| Worker 注册与能力 | 路由和副作用准入；未知Replay能力不能推断为可安全重放 |

### 3.2 三个表的最小 seed 例子

配置使用Console正式API或当前配置包，完整示例见[配置包设计](../design/tenant-config-package-excel-9plus2-design.md)及根test-data。新格式不需要租户pipeline_definition/pipeline_step_definition。

最小单作业链需要Workflow定义/图、被JOB节点引用的Job定义，以及该类型实际需要的模板/通道。不要直接更新数据库来绕过版本、租户、审批或配置修订守卫；配置变更仅作用于后续接纳/物化的有效版本。

## 4. 跟着任务走一遍（三类全程）

下列示例聚焦JOB节点内部；完整入口均先接纳对应Workflow，再按节点创建JobInstance。示例耗时、ID和记录数是历史场景数据，不代表当前容量或验收结果。

下面三个例子都来自 2026-04-25 真实跑通的 instance，`pipeline_step_run` 的耗时是真实数据。
当前顶层 Trigger 契约以受管单作业 Workflow 的 `workflowDefinitionId` 为目标；示例中的 JobCode 只标识执行内容，不作为根触发字段。

### 4.1 EXPORT 全程 — `TC_EXPORT_RISK_ALERT`

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
sequenceDiagram
  participant C as 调用方
  participant T as Trigger
  participant O as Orchestrator
  participant K as Kafka
  participant W as Export Worker
  participant B as 业务资源 / 文件存储
  C->>T: Workflow 目标与来源身份
  T->>T: 保存请求 + Trigger Outbox
  T->>K: Relay 发布 launch
  K->>O: 消费请求
  O->>O: WorkflowRun → JOB → JobInstance / Partition
  O->>O: 状态与 Task Outbox 同事务
  O->>K: Relay 发布任务
  K->>W: 投递任务
  W->>O: HTTP CLAIM
  O-->>W: invocation / 租约 / 有效配置
  W->>B: 读取业务数据，生成并存储文件
  B-->>W: 业务结果
  W->>W: 登记文件元数据
  W->>O: HTTP REPORT
  O->>O: 聚合 Job / Node / Workflow 状态
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
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
sequenceDiagram
  participant C as 调用方
  participant T as Trigger
  participant O as Orchestrator
  participant K as Kafka
  participant W as Import Worker
  participant B as 业务资源 / 文件存储
  C->>T: Workflow 目标与来源身份
  T->>T: 保存请求 + Trigger Outbox
  T->>K: Relay 发布 launch
  K->>O: 消费请求
  O->>O: WorkflowRun → JOB → JobInstance / Partition
  O->>O: 状态与 Task Outbox 同事务
  O->>K: Relay 发布任务
  K->>W: 投递任务
  W->>O: HTTP CLAIM
  O-->>W: invocation / 租约 / 有效配置
  W->>B: 读取输入文件，解析校验并写入业务库
  B-->>W: 业务结果
  W->>W: 登记导入结果
  W->>O: HTTP REPORT
  O->>O: 聚合 Job / Node / Workflow 状态
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
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
sequenceDiagram
  participant C as 调用方
  participant T as Trigger
  participant O as Orchestrator
  participant K as Kafka
  participant W as Dispatch Worker
  participant B as 业务资源 / 文件存储
  C->>T: Workflow 目标与来源身份
  T->>T: 保存请求 + Trigger Outbox
  T->>K: Relay 发布 launch
  K->>O: 消费请求
  O->>O: WorkflowRun → JOB → JobInstance / Partition
  O->>O: 状态与 Task Outbox 同事务
  O->>K: Relay 发布任务
  K->>W: 投递任务
  W->>O: HTTP CLAIM
  O-->>W: invocation / 租约 / 有效配置
  W->>B: 读取文件与通道配置，投递并核对回执
  B-->>W: 业务结果
  W->>W: 登记投递结果
  W->>O: HTTP REPORT
  O->>O: 聚合 Job / Node / Workflow 状态
```

`pipeline_step_run` 记录：

```
DISPATCH_PREPARE   SUCCESS  30ms
DISPATCH_DISPATCH  SUCCESS  54ms
DISPATCH_ACK       SUCCESS  10ms
DISPATCH_COMPLETE  SUCCESS  8ms
```

> 异常路径（DISPATCH_RETRY + DISPATCH_COMPENSATE 怎么跑通）参考 [`../runbook/worker-stage-coverage.md` §3.4](../verifications/worker-stage-coverage-2026-04-25.md)，那里有用坏 channel 触发 3 轮 retry 的真实 `pipeline_step_run` 12 行记录。

### 4.4 三个例子的对比

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart TB
  subgraph EXPORT["EXPORT · 生成文件"]
    EB[("业务数据")] --> EW["Export Worker"]
    EW --> ES["对象存储"]
    EW --> EF[("文件元数据")]
  end
```

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart TB
  subgraph IMPORT["IMPORT · 消费文件"]
    IS["输入文件"] --> IW["Import Worker"]
    IW --> IB[("业务数据")]
    IW --> IF[("文件元数据")]
  end
```

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart TB
  subgraph DISPATCH["DISPATCH · 投递文件"]
    DS["对象存储"] --> DW["Dispatch Worker"]
    DW --> DC["外部通道"]
    DW --> DF[("投递元数据")]
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
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
stateDiagram-v2
  [*] --> CREATED
  CREATED --> WAITING: 等待准入
  CREATED --> READY: 直接准入
  WAITING --> READY: 条件满足
  READY --> RUNNING: CLAIM
  RUNNING --> SUCCESS: 执行成功
  RUNNING --> RETRYING: 可重试失败
  RETRYING --> READY: 到期再派发
  RETRYING --> FAILED: 耗尽
  RUNNING --> FAILED: 不可重试
  WAITING --> CANCELLED: 取消
  RUNNING --> CANCELLED: 取消生效
  RUNNING --> TERMINATED: 强制终止
  SUCCESS --> [*]
  FAILED --> [*]
  CANCELLED --> [*]
  TERMINATED --> [*]
```

job_instance 的状态聚合：

```
INSTANCE_STATUS = CREATED → WAITING → READY → RUNNING →
                  SUCCESS / FAILED / PARTIAL_FAILED / CANCELLED / TERMINATED
```

`PARTIAL_FAILED` 表示作业实例的多个分区结果混合；具体触发条件由各类型聚合策略决定，不能等同于整 Workflow 成功。

---

## 6. 为什么这样设计 — 几个关键不变量

1. **DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT**：所有任务派发必走这条主链，不允许 worker 直连 DB 改 `job_instance`。Orchestrator 是唯一状态主机。
2. **outbox_event 必须与状态写入同一事务**：原子保存状态与待投递事件；Kafka 发布可延迟或重复，由 relay 重试与消费幂等收敛，不承诺跨系统同时可见。
3. **Worker 必须 CLAIM 才能执行**：partition 通过 `lease_expire_at` 实现 worker 抢占；worker 进程崩溃后租约到期，partition 自动可被其他 worker 接管。
4. **模板与渠道有独立配置来源**：新运行按快照引用有效配置；修改当前定义不应悄悄改写排队/重试中的旧运行。密钥撤销等实时安全策略按各自执行边界生效。
5. **系统 Pipeline 固定阶段与顺序**：当前由 `SystemPipelineCatalog` 按 Job 类型选择阶段，不接受租户定义阶段子集或改序；阶段业务参数进入 Job 冻结快照，不能覆盖控制流。历史阶段实现缺失问题见 [worker-stage-coverage-2026-04-25.md](../verifications/worker-stage-coverage-2026-04-25.md)。

---

## 7. Outbox 机制详解（DB → Kafka 的桥）

§6 第 1、2 条不变量都涉及 `outbox_event`。这一节讲清楚它怎么落地。

### 7.1 解决什么问题

orchestrator 派发任务时要做两件事：
1. 把 `job_instance` / `partition` 写进 PostgreSQL
2. 把"task dispatch"事件发到 Kafka 让 worker 消费

PostgreSQL + Kafka 没原生分布式事务。**先发 Kafka 后写 DB**：DB 失败 → worker 收到指向残留任务的事件；**先写 DB 后发 Kafka**：Kafka 失败 → 任务永远不会被 worker 看到。

**Outbox Pattern 的解法**：把"我要发的消息"也写到一张表 `outbox_event`，**和业务数据写在同一个 PG 事务里**；Kafka 投递解耦到独立 poller。

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
sequenceDiagram
  participant A as 业务服务
  participant DB as 平台数据库
  participant R as Outbox Relay
  participant K as Kafka
  A->>DB: BEGIN
  A->>DB: 更新状态 + 插入 NEW 事件
  A->>DB: COMMIT
  R->>DB: 读取到期事件并 CAS 认领
  DB-->>R: PUBLISHING 事件
  R->>K: 发送稳定 eventKey
  alt 收到 Kafka ACK
    K-->>R: ACK
    R->>DB: 标记 PUBLISHED
  else 发送失败
    R->>DB: 标记 FAILED + 退避时间
  end
```

业务事务一旦 commit，事件就一定在 outbox 里；Kafka 暂挂、producer 重启、网络分区，业务不阻塞，最差只是延迟。

### 7.2 publish_status 状态机

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
stateDiagram-v2
  [*] --> NEW
  NEW --> PUBLISHING: CAS 认领
  FAILED --> PUBLISHING: 到期重试
  PUBLISHING --> PUBLISHED: Kafka ACK
  PUBLISHING --> FAILED: 失败 / 陈旧回收
  FAILED --> GIVE_UP: 尝试耗尽
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
- **优雅下线**：`gracefulShutdown.isDraining()=true` 时跳过本轮 + 跳过抢锁（防 Lettuce 已 STOPPED 还去 Redis 抢锁抛 `IllegalStateException`，详见 docs/agent-baseline.md 2026-04-24）。

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

## 7.8 ExecutionMode.INCREMENTAL — 增量水位回路

> 设计依据：[`../design/batch-classification-and-gaps.md`](../design/batch-classification-and-gaps.md) §4.1。
> 落地：commit `7584359f`（P0-1 模型层 + V73 migration）+ `0d64f69c`（P0-1.5 运行时双向打通）。

### 7.8.1 用途

`ExecutionMode = INCREMENTAL` 时，框架托管"上次成功跑到哪 → 这次从哪继续"的水位状态机。worker 业务侧只需读 IN 拼增量 SQL、完成时写 OUT，**框架负责持久化 + 跨实例传递**。

`FULL`/`CDC` 模式不走这套回路（FULL 每次全量；CDC 是占位枚举值，worker 暂不实现）。

### 7.8.2 一张图看完整回路

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart TB
  PREV["上次成功 Job · highWaterMarkOut"] -->|"INCREMENTAL"| IN["本次 highWaterMarkIn"]
  IN --> O["Orchestrator · 有效任务配置"]
  O --> TX[("Task Outbox")]
  TX --> R["Relay"] --> K["Kafka"] --> W["Worker · 执行上下文"]
  W --> B["业务插件 · 读取 IN / 生成 OUT"]
  B -->|"成功 REPORT + 非空 OUT"| O2["Orchestrator · 受控持久化"]
  O2 --> NEXT["Job highWaterMarkOut"]
  NEXT -. "下次增量运行" .-> IN
```

### 7.8.3 关键不变量

| 不变量 | 含义 |
|---|---|
| 仅成功路径推水位 | `applyTaskOutcome` 在 success 分支才调 `updateHighWaterMarkOut`;失败/重试/取消保留旧 OUT |
| OUT 为 null 不推 | worker 没写 OUT(业务暂不接 INCREMENTAL)就别覆盖旧值;允许"灰度切到 INCREMENTAL 再不推水位"过渡 |
| OUT 持久化无版本检查 | 水位是单调推进的最终一致字段,并发并不会破坏正确性,SQL 不带 `version = #{expectedVersion}` |
| IN/OUT 跨子作业不传递 | DAG 的 JOB 节点子作业有自己的 jobDefinition,各自有自己的水位序列;终态直接关联所属 NodeRun；不再经父虚拟任务传递水位 |
| 字段位 v1 schema 可选 | `TaskDispatchMessage.highWaterMarkIn` 是 v1 schema 末尾追加字段;旧 worker Jackson 忽略未知字段 → 滚动升级安全 |

### 7.8.4 关键代码位

| 位置 | 干啥 |
|---|---|
| `batch-common/.../enums/ExecutionMode.java` | 枚举 FULL / INCREMENTAL / CDC |
| `batch-common/.../kafka/TaskDispatchMessage.java` | 末尾 `highWaterMarkIn` 字段 |
| `batch-orchestrator/.../service/DefaultLaunchService.java` `resolveHighWaterMarkIn` | 启动时算 IN |
| `batch-orchestrator/.../mapper/JobInstanceMapper.xml` `selectLastSuccessByJobDefinition` | 按 `finished_at desc nulls last, id desc` 取上次成功 |
| `batch-orchestrator/.../mapper/JobInstanceMapper.xml` `updateHighWaterMarkOut` | 单字段 update,无版本检查 |
| `batch-orchestrator/.../engine/TaskDispatchOutboxService.java` | 嵌进 outbox 消息 |
| `batch-worker/core/.../infrastructure/PipelineRuntimeKeys.java` | `HIGH_WATER_MARK_IN` / `HIGH_WATER_MARK_OUT` 键名 |
| `batch-worker/core/.../infrastructure/DefaultTaskExecutionWrapper.java` | 注入 IN 到 attributes,从 attributes 取 OUT 填 report |
| `batch-orchestrator/.../service/DefaultTaskOutcomeService.java` `applyTaskOutcome` | success 路径回写 OUT |

### 7.8.5 兼容性矩阵

| 升级路径 | 行为 |
|---|---|
| 旧 worker + 新 orchestrator | Kafka 消息有 `highWaterMarkIn`;旧 worker Jackson 忽略未知字段 → 当 FULL 跑 |
| 新 worker + 旧 orchestrator | 消息没 `highWaterMarkIn` → null → worker 当 FULL;新 worker 上报 `highWaterMarkOut` 给旧 orchestrator → DTO 没该字段被忽略 |
| 旧 `job_definition`(execution_mode='FULL' 默认) | `resolveHighWaterMarkIn` 直接 `return null` → 现行行为不变 |
| 失败/重试 | 不更新 high_water_mark_out → 重新跑时 IN 仍是上次成功的 OUT,不会跳过未处理数据 |

### 7.8.6 业务侧使用模板

业务 pipeline(IMPORT 的 PARSE/LOAD,EXPORT 的 GENERATE 等)按需读写 attributes:

```java
// 阶段开始读 IN
String hwmIn = (String) ctx.getAttributes().get(PipelineRuntimeKeys.HIGH_WATER_MARK_IN);

// 拼 SQL
String where = hwmIn == null ? "" : " WHERE update_time > :highWaterMarkIn";
// 使用命名参数绑定 highWaterMarkIn，禁止把业务水位直接拼入 SQL。

// 跑完业务逻辑后,把本批次最大 update_time 写回
ctx.getAttributes().put(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, maxUpdateTime.toString());
```

框架不做"自动推进"——worker 显式不写 OUT 就保持上次值不变,由业务保证幂等(下次跑 IN 不变,SQL 仍 `WHERE update_time > IN` 拿到本批次没处理完的剩余数据)。

---

## 7.9 PROCESS WAP+bookends — 五段加工流水线

### 7.9.1 用途

PROCESS 是 IMPORT/EXPORT/DISPATCH 之外的第四类文件 Worker,定位"系统内部数据加工"(聚合 / 清洗 / 状态推进)。落地依据:[`docs/design/batch-classification-and-gaps.md`](../design/batch-classification-and-gaps.md) §4.5。

不是简单"跑一段 SQL"。默认 JSONB 模式是 **WAP+bookends**(Write-Audit-Publish + 前后置):**先写 staging,后审核,再原子 publish**。与 dbt build / Iceberg branch / Netflix Atlas 的成熟数据平台一致,避免异常数据落到生产 target 表。`stagingMode=DIRECT` 是给无 staging audit 需求的大吞吐 copy 场景的显式快路径,以 `INSERT ... SELECT ... ON CONFLICT` 直接发布。

### 7.9.2 一张图看完整回路

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart TB
  CFG["Job 冻结参数 / 有效 spec"] --> A["PREPARE"]
  A --> B["COMPUTE"] --> C["VALIDATE"] --> D["COMMIT"] --> E["FEEDBACK"]
  SRC[("业务源表")] -->|"JSONB 读取"| B
  B -->|"JSONB 写入"| ST[("业务 staging")]
  ST --> C
  ST --> D
  SRC -->|"DIRECT 读取并发布"| D
  D --> TGT[("业务目标表")]
  C -->|"未通过"| F["失败与核查证据"]
  E -->|"REPORT / 输出水位"| O["Orchestrator"]
  O --> STATE[("平台运行状态")]
  E -->|"JSONB 清理本批 staging"| ST
```

### 7.9.3 关键不变量

- **target 表干净**:VALIDATE 失败时 COMMIT 不跑,target 完全不变;失败本批数据留在 staging 供运维复盘
- **batchKey 唯一性**:PREPARE 阶段生成 `process-<taskId>-<traceId>`,5 个 stage 共享;不同 task 不同 batchKey,staging 行天然隔离
- **原子 publish**:JSONB 模式 COMMIT 是单 SQL `INSERT ... SELECT FROM staging ... ON CONFLICT DO UPDATE`;DIRECT 模式是单 SQL `INSERT ... SELECT FROM sourceSql ... ON CONFLICT ...`,不存在"写一半"——PG 单语句要么全成要么全失败
- **staging 不堆积**:JSONB 成功路径 FEEDBACK 必清(`DELETE WHERE batch_key`);失败路径 staging 保留供 forensics,直到下次 PREPARE 同 batchKey 才清(实际由 retry 触发的新 task 是新 batchKey,旧 batchKey 需运维按 staged_at 清理)。DIRECT 模式不写 staging。
- **plugin opt-in**:`ProcessComputePlugin` 5 个 lifecycle 方法都有默认 no-op,自定义插件只重写 `compute()` 也合法,框架仍跑全 5 stage

### 7.9.4 关键代码位

| 位置 | 职责 |
|---|---|
| `batch-worker/process/.../stage/DefaultProcessStageExecutor.java` | 5 段循环；从系统模板与冻结参数解析 COMPUTE 插件并缓存到 context |
| `batch-worker/process/.../stage/ProcessComputePlugin.java` | 5 lifecycle 接口 (prepare/compute/validate/commit/feedback,默认 no-op) |
| `batch-worker/process/.../sql/SqlTransformComputePlugin.java` | 内置插件:解析 spec / JSONB staging 或 DIRECT 发布 / 跑 validations / `jsonb_populate_record` 或 `INSERT...SELECT` 发布 / 清 staging |
| `batch-worker/process/.../sql/SqlTransformComputeSpec.java` | step_params 解析 + identifier 校验 + `stagingMode` + `validations` 列表 |
| `batch-worker/process/.../sql/SqlTransformComputeSqlValidator.java` | jsqlparser AST 校验 + SELECT-only + schema allowlist + `validateUserCheckSelect`(给 VALIDATE 用,跳过 schema allowlist) |
| `db/migration/V75__add_process_staging_table.sql` | 共享 `batch.process_staging` 表(JSONB payload + batch_key/staged_at 索引) |

### 7.9.5 业务侧使用模板

```yaml
# Job 的 PROCESS 类型化执行参数示例（YAML表示）
sqlTransformCompute:
  sourceSql: |
    SELECT tenant_id, account_id, biz_date,
           sum(amount) AS total_amount,
           max(event_id) AS high_water_mark
    FROM biz.process_order_event
    WHERE tenant_id = :tenantId
      AND biz_date = cast(:bizDate as date)
      AND event_id > cast(:highWaterMarkIn as bigint)
    GROUP BY tenant_id, account_id, biz_date
  targetSchema: biz
  targetTable: process_account_summary
  writeMode: UPSERT          # INSERT / UPSERT / INSERT_IGNORE
  columns:                   # source → target 映射
    - {source: tenant_id, target: tenant_id}
    - {source: account_id, target: account_id}
    - {source: biz_date, target: biz_date}
    - {source: total_amount, target: total_amount}
    - {source: high_water_mark, target: high_water_mark}
  conflictColumns: [tenant_id, account_id, biz_date]
  watermarkColumn: high_water_mark   # max(列) 作为本批 highWaterMarkOut
  validations:               # 可选,失败阻 commit
    - name: total_non_negative
      checkSql: |
        SELECT bool_and((payload->>'total_amount')::numeric >= 0) AS pass,
               'negative total amount detected' AS message
        FROM batch.process_staging
        WHERE batch_key = :batchKey
```

业务调用方通过当前 Job 配置提交 COMPUTE 阶段参数与插件选择，并由根运行冻结。阶段由系统模板产生，Worker 合并允许的业务参数；不能 INSERT 租户 Pipeline 定义替换执行链，也不能用参数跳转、减少或重排阶段。具体参数键按 `SystemPipelineStepConfiguration` 与 PROCESS 插件解析契约填写。

### 7.9.6 与 ExecutionMode.INCREMENTAL(§7.8)的协作

PROCESS 与 ExecutionMode 正交:既可以做 FULL 全量(每次重新聚合),也可以做 INCREMENTAL 增量(读取上次 OUT 当本次 IN)。`watermarkColumn` 字段就是给 INCREMENTAL 模式用的——COMPUTE 阶段算出 `max(watermarkColumn)` 写入 `attributes.HIGH_WATER_MARK_OUT`,FEEDBACK 透传 → orchestrator 回写 `job_instance.high_water_mark_out`,下次该 job 启动时被加载为 `highWaterMarkIn` 命名参数,业务 SQL 用 `WHERE event_id > :highWaterMarkIn` 取增量。

### 7.9.7 资源上限与孤儿清理(P1-6 / P1-7)

| 防护 | 配置 | 默认 | 触发后果 |
|---|---|---|---|
| **maxStagedRows**(P1-6) | `step_params.sqlTransformCompute.maxStagedRows` | `1_000_000` | COMPUTE 写完 staging 后 row count > limit 立即失败(`PROCESS_STAGED_OVERFLOW`)+ 同步删本批 staging,target 完全不写,task FAILED |
| **orphan staging cleanup**(P1-7/PART-02) | `batch.worker.process.staging-cleanup.{enabled,deletion-enabled,interval,retention-hours,batch-size,pre-create-days}` | enabled=true / deletion-enabled=false / 15 分钟 / 24 小时 / 5000 行 / 未来 2 天 | ShedLock 调度预建分区；安全回收只处理超期且已被新 generation 取代的旧代，在平台实例锁下确认后，于业务事务封存并分块删除。默认仍关闭；当前代和整分区保留。启动审计使用租户/generation RLS 上下文；暂停/重试/归档、多库、崩溃与容量验证完成前不得启用。 |

> 调优建议:
>
> - 业务 SQL 写错或笛卡尔积可能瞬时撑大几千万行,默认 100 万的 `maxStagedRows` 足以拦截大多数事故,生产可按业务体量上调到 500 万 / 1000 万。
> - `retentionHours` 默认 24 小时是旧 generation 的最短保留；不能单独授权删除当前 generation 或按日期 DROP 分区。
> - 多 worker 实例只有一个会真正跑 cleanup(ShedLock 互斥);单 worker 部署可以把 enabled 关掉,等手工触发。

### 7.9.8 边界与重跑语义(P2-1 / P2-2 / P2-7)

**writeMode 的重跑语义(P2-1)**:

| `writeMode` | 重跑/retry 行为 | 推荐场景 |
|---|---|---|
| `INSERT` | 撞 PK/UK 直接 SQLException;只适合无唯一键的临时聚合表 | 不推荐生产用 |
| `UPSERT`(默认推荐) | `ON CONFLICT (...) DO UPDATE SET col = EXCLUDED.col` 幂等覆盖,retry 安全 | 大多数业务聚合 / 状态推进 |
| `INSERT_IGNORE` | `ON CONFLICT (...) DO NOTHING`,retry 时已有行保持原值 | 历史数据不可改写的事实表追加 |

**JSONB 列类型矩阵(P2-2)**:`jsonb_populate_record(NULL::biz.target, payload)` 的反序列化已验证类型:

- ✅ 已验证:`text / varchar / bigint / int / numeric / boolean / date / timestamptz`
- ⚠️ 待验证:`bytea`(JSONB 不能直接 hex/base64,需要业务层显式转码)、`enum`(PG 自定义 enum 类型)、`uuid`(应该 OK 但未单测)
- ❌ 不支持:数组类型(`int[]` / `text[]`)、复合类型(custom composite)、PostGIS 几何类型 — 需走应用层转换或自定义 plugin

业务上线前对非常用类型做最小冒烟即可。

**自定义 ProcessComputePlugin SPI 边界(P2-7)**:

`ProcessComputePlugin` 5 个 lifecycle 方法都有默认 no-op,自定义插件按需 opt-in:

```java
@Component
public class MyProcessPlugin implements ProcessComputePlugin {
  @Override public String implCode() { return "myDailyAggregate"; }

  // 简单插件:只重写 compute(),其它 stage 走默认 no-op
  @Override public ProcessStageResult compute(ProcessJobContext ctx) {
    // 自己用 JdbcTemplate / MyBatis 直接读源表写 target 表(不走 staging)
    int rows = doYourBusinessLogic(ctx);
    ctx.getAttributes().put("processedCount", rows);
    return ProcessStageResult.success(ProcessStage.COMPUTE);
  }
}
```

**关键约束:**

1. **不写 staging 的自定义插件,等于绕过 WAP+bookends 保障** —— VALIDATE/COMMIT/FEEDBACK 没事可做,失败时无法 forensics 也无法 atomic publish。除非业务能用其他机制(target 表自身幂等键 + 应用层事务)代偿,否则建议至少把数据先 INSERT staging 让框架走完整 5 段。
2. **写 staging 必须遵守 staging 表 schema 与 batch_key 隔离** —— `tenant_id / target_schema / target_table / batch_key` 4 列必填(P0-2 多租户隔离前提)。
3. **highWaterMarkOut 上报方式** —— 业务在 compute()/feedback() 里 `ctx.getAttributes().put(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, "...")`,DefaultTaskExecutionWrapper 会回报 orchestrator 持久化到 `job_instance.high_water_mark_out`。
4. **失败保留语义** —— compute() 返回 failure 即跳过后续 stage;插件应在 compute() 内部决定要不要清自己写的 staging,框架只在框架视角的 P1-6/P1-7 补充清理。

完整代码示例见 `batch-worker/process/src/test/java/.../ProcessPipelineE2eIT.java` 的 `e2eProcessCompute` 测试桩。

---

## 8. 进一步阅读

| 查询场景 | 文档 |
|---|---|
| 一条任务的具体表读写 | [`runtime-module-communication.md`](./runtime-module-communication.md) |
| 模块边界与版本基线 | [`architecture-truth.md`](./architecture-truth.md) |
| 核心实体关系 | [`core-model.md`](./core-model.md) |
| Worker 插件如何注册 | [`worker-plugins.md`](./worker-plugins.md) |
| Kafka topic 命名 | [`kafka-topic-plan.md`](./kafka-topic-plan.md) |
| 真实端到端验证（含 RETRY/COMPENSATE） | [`worker-stage-coverage-2026-04-25.md`](../verifications/worker-stage-coverage-2026-04-25.md) |
| 编码与字典约定 | [`/docs/agent-baseline.md`](../agent-baseline.md) |
