# 作业依赖与编排指南 — Workflow DAG

> 面向"我要让一个作业等另一个作业完成才跑"或者"等多个作业都完成才跑"的人。
> 系统通过 `workflow_definition` + `workflow_node` + `workflow_edge` 三表把多个 `job_definition` 编排成 DAG。

---

## 1. 一图看懂依赖怎么表达

```mermaid
flowchart LR
  subgraph 单上游
    A1[JOB_A] -- SUCCESS --> A2[JOB_B]
    A1 -- FAILURE --> A3[JOB_ERR<br/>告警/补偿]
  end

  subgraph 多上游全部
    B1[JOB_A] -- SUCCESS --> BG{GATEWAY<br/>joinMode=ALL}
    B2[JOB_B] -- SUCCESS --> BG
    B3[JOB_C] -- SUCCESS --> BG
    BG --> B4[JOB_D<br/>等齐 A B C]
  end

  subgraph 多上游N个
    C1[JOB_A] -- SUCCESS --> CG{GATEWAY<br/>joinMode=N_OF<br/>joinThreshold=2}
    C2[JOB_B] -- SUCCESS --> CG
    C3[JOB_C] -- SUCCESS --> CG
    CG --> C4[JOB_D<br/>3 中 2 即触发]
  end
```

| 形态 | 用法 | 何时选 |
|---|---|---|
| 单上游 | `from→to` 直接配 SUCCESS / FAILURE 边 | 串行链路 |
| 多上游全部 | GATEWAY 节点 + `joinMode=ALL` | 等齐所有数据源（"日终全部数据准备完才汇总"）|
| 多上游 N 个 | GATEWAY 节点 + `joinMode=N_OF, joinThreshold=N` | 容忍部分失败（"3 个国家分支 2 个成功就发 EOD"）|
| 多上游任一 | GATEWAY 节点 + `joinMode=ANY` | 抢先触发（任何一个完就跑下游）|

---

## 2. 数据模型

### 2.1 三张表

```mermaid
erDiagram
  workflow_definition ||--o{ workflow_node : has
  workflow_definition ||--o{ workflow_edge : has
  workflow_node }o--|| job_definition : "JOB.related_job_code"
  workflow_node }o--|| pipeline_definition : "TASK/FILE_STEP.related_pipeline_code"

  workflow_definition {
    bigint id PK
    string tenant_id
    string workflow_code
    string workflow_type "DAG/PIPELINE/MIXED"
    int    version
    bool   enabled
  }
  workflow_node {
    bigint id PK
    bigint workflow_definition_id FK
    string node_code "唯一标识 START/END/JOB_X/MERGE..."
    string node_type "START/END/JOB/TASK/FILE_STEP/GATEWAY/WAIT"
    string related_job_code "node_type=JOB 时指向 job_definition.job_code"
    string related_pipeline_code
    string worker_group
    int    node_order
    string retry_policy
    int    retry_max_count
    jsonb  node_params "{joinMode,joinThreshold,...}"
    bool   enabled
  }
  workflow_edge {
    bigint id PK
    bigint workflow_definition_id FK
    string from_node_code
    string to_node_code
    string edge_type "SUCCESS/FAILURE/CONDITION/ALWAYS"
    string condition_expr "edge_type=CONDITION 时的表达式"
    bool   enabled
  }
```

### 2.2 枚举值（与 `batch-common/enums` 一致）

| 枚举 | 取值 | 含义 |
|---|---|---|
| `WorkflowType` | `DAG` / `PIPELINE` / `MIXED` | DAG 自由有向无环图；PIPELINE 严格线性；MIXED 混合 |
| `WorkflowNodeType` | `START` / `END` / `JOB` / `TASK` / `FILE_STEP` / `GATEWAY` / `WAIT` | JOB = 嵌套独立子作业；TASK = workflow 内 pipeline step；GATEWAY = 路由/汇聚；WAIT = Sensor 等待节点（当前未完成稳定 E2E 验收） |
| `WorkflowEdgeType` | `SUCCESS` / `FAILURE` / `CONDITION` / `ALWAYS` | 边的触发条件 |
| `WorkflowJoinMode` | `ALL` / `ANY` / `N_OF` | GATEWAY 等多上游的 join 策略 |

---

## 3. 租户 Workflow 完整运行流程（当前实现）

本节描述已经进入主运行链的实现事实。一次租户 Workflow 不是把整张 DAG 下发给某个
Worker，而是由 Orchestrator 持有 DAG 游标，按节点逐步物化任务并收敛状态。

### 3.1 端到端时序图

```mermaid
sequenceDiagram
    autonumber
    actor Tenant as 租户用户/上游系统
    participant Console as Console API
    participant Trigger as batch-trigger
    participant TDB as trigger_request<br/>trigger_outbox_event
    participant TK as Kafka<br/>batch.trigger.launch.v1
    participant Orch as batch-orchestrator
    participant PDB as PostgreSQL<br/>batch_platform
    participant DK as Kafka<br/>batch.task.dispatch.*
    participant Worker as 五类 Worker
    participant Target as 业务库/MinIO/SFTP/HTTP

    Note over Tenant,Console: 定义期：同 tenant 下配置 job_definition(job_type=WORKFLOW)<br/>以及 workflow_definition/node/edge
    Tenant->>Console: 保存并启用 Workflow 定义
    Console->>PDB: 校验 DAG、租户引用、版本后写定义

    alt Quartz 定时触发（生产主路径）
        Trigger->>TDB: 同事务写 trigger_request + trigger_outbox_event
    else 人工/补数/replay/文件等齐触发
        Tenant->>Console: launch/rerun/replay
        Console->>Trigger: 提交触发命令
        Trigger->>TDB: 同事务写 trigger_request + trigger_outbox_event
    end

    Trigger->>TK: Relay 发布 LaunchEnvelope
    TK->>Orch: TriggerLaunchConsumer 消费
    Orch->>PDB: 校验 tenant/job/workflow/dedup/readiness/批次日

    rect rgb(236, 248, 255)
        Note over Orch,PDB: T1 准备事务：先提交稳定事实源
        Orch->>PDB: INSERT job_instance(CREATED)
        Orch->>PDB: INSERT workflow_run(CREATED)
        Orch->>PDB: INSERT START node_run(SUCCESS)
    end

    rect rgb(241, 250, 241)
        Note over Orch,PDB: T2 派发事务：节点运行态、任务和 Outbox 原子提交
        Orch->>PDB: 解析 START 出边、join、condition、跨日依赖
        Orch->>PDB: INSERT node_run/partition/task/outbox
        Orch->>PDB: job_instance/workflow_run → RUNNING 或 WAITING
    end

    PDB->>DK: Outbox Relay 发布任务
    DK->>Worker: 按 Import/Export/Process/Dispatch/Atomic 路由
    Worker->>Orch: claim(taskId, tenantId, workerId)
    Worker->>Orch: renew lease / heartbeat
    Worker->>Target: 执行业务副作用
    Worker->>Orch: report SUCCESS/FAILED + outputs

    rect rgb(255, 248, 235)
        Note over Orch,PDB: report 事务：完成当前节点并推进 DAG
        Orch->>PDB: 更新 task/partition/node_run
        Orch->>PDB: 保存 workflow_node_run.output
        Orch->>PDB: 解析 SUCCESS/FAILURE/CONDITION/ALWAYS 出边
        Orch->>PDB: 创建下游 task + outbox，更新 current_node_code
    end

    loop 直到 END 可达且所有活跃节点终结
        PDB->>DK: 发布下游节点任务
        DK->>Worker: 执行下一节点
        Worker->>Orch: report
    end

    Orch->>PDB: workflow_run/job_instance → SUCCESS/FAILED/TERMINATED
    Orch->>PDB: 写 terminal outbox、审计与结果版本
    Console-->>Tenant: 查询运行图、节点输出、错误和重跑入口
```

### 3.2 运行态对象关系图

```mermaid
erDiagram
  job_definition ||--o{ job_instance : "tenant + jobCode"
  workflow_definition ||--o{ workflow_run : snapshots
  job_instance ||--|| workflow_run : owns
  workflow_run ||--o{ workflow_node_run : advances
  job_instance ||--o{ job_partition : materializes
  job_partition ||--o{ job_task : executes
  job_task ||--o{ outbox_event : dispatches
  job_instance ||--o{ job_instance : "JOB node child"

  job_instance {
    bigint id PK
    string tenant_id
    string instance_status
    date biz_date
    int run_attempt
    string trace_id
    jsonb params_snapshot
  }
  workflow_run {
    bigint id PK
    string tenant_id
    bigint related_job_instance_id
    string run_status
    string current_node_code
    bool dry_run
  }
  workflow_node_run {
    bigint id PK
    bigint workflow_run_id
    string node_code
    string node_status
    int run_seq
    jsonb output
  }
  job_task {
    bigint id PK
    string tenant_id
    string task_status
    string task_type
    jsonb task_payload
  }
```

`workflow_run.current_node_code` 是活跃节点集合的摘要；真正的节点历史以
`workflow_node_run(workflow_run_id, node_code, run_seq)` 为准。JOB 节点会拉起独立子
`job_instance`，父实例用虚拟 partition/task 等待子实例终态，因此嵌套作业仍复用统一的分区聚合逻辑。

### 3.3 分阶段执行表

| 阶段 | 责任模块 | 核心动作 | 主要持久化结果 |
|---|---|---|---|
| 0. 定义 | Console API | 保存、静态校验并版本化租户 DAG | `job_definition`、`workflow_definition/node/edge`、定义版本 |
| 1. 触发 | Trigger | 生成 requestId/dedupKey，事务性记录触发 | `trigger_request`、`trigger_outbox_event` |
| 2. 接收 | Orchestrator | 按 `tenantId + jobCode` 读取启用定义，处理重复和 RERUN | 触发接受状态、traceId |
| 3. T1 准备 | Orchestrator | 创建父实例、Workflow 实例和已完成 START 节点 | `job_instance`、`workflow_run`、START `node_run` |
| 4. T2 派发 | Orchestrator | 解析初始节点，做配额/资源准入，创建任务和 Outbox | `partition/task/outbox`，实例转 RUNNING/WAITING |
| 5. 执行 | Worker | consume → claim → execute → renew → report | Worker 不直接改核心调度状态 |
| 6. 节点收口 | Orchestrator | 聚合分片，写节点终态和 outputs | `workflow_node_run.output`、task/partition 终态 |
| 7. DAG 推进 | Orchestrator | 计算出边、join 和条件，物化下游节点 | 新 task/outbox、更新活跃节点集合 |
| 8. 总体收口 | Orchestrator | 所有活跃节点结束后收敛 Workflow 和父实例 | SUCCESS/FAILED/dry-run/TERMINATED、terminal outbox |
| 9. 运维闭环 | Console API | 查询、暂停/恢复、终止、重跑、补偿、审计 | 操作审计、retry/DLQ/replay/补偿记录 |

### 3.4 节点派发规则

| 节点类型 | 是否创建 Worker 任务 | 当前运行语义 |
|---|---:|---|
| `START` | 否 | T1 中直接记录 SUCCESS，再解析初始出边 |
| `TASK` / `FILE_STEP` | 是 | 构造 SchedulePlan，创建真实 partition/task，经 Outbox 发到对应 Worker |
| `JOB` | 子作业执行 | 创建父虚拟 partition/task，再启动同租户子 `job_instance`；子实例终态回写父虚拟 task |
| `GATEWAY` | 否 | Orchestrator 内执行 fork/join，支持 ALL/ANY/N_OF |
| `END` | 否 | 入边满足后记录终态节点；没有活跃节点后收敛 Workflow |
| `WAIT` | 设计为否 | Sensor SPI、轮询器和状态机已有代码，但 ADR-028 仍为 Proposed；未完成稳定 E2E 验收前，不作为生产主链承诺 |

### 3.5 参数和输出传递

下游 task payload 按以下顺序合并，后层只在设计允许的位置覆盖或补齐前层：

1. Workflow 根启动参数；
2. 已成功上游分区的白名单输出（`fileId/fileCode/batchNo/recordCount/bizDate`）；
3. 当前节点 `node_params`，其中 ADR-009 DSL 引用会用当前 `workflow_run` 上下文解析；
4. `workflowNodeCode/workflowNodeType/targetJobCode` 等运行元数据。

Worker 成功上报的 `outputs` 写入 `workflow_node_run.output`。下游可以显式引用
`$.nodes.<nodeCode>.output.<key>` 或 `$.workflowRun.<key>`；多分片节点先按节点聚合，再推进 DAG。

### 3.6 状态与分支规则

| 对象 | 主要状态 | 说明 |
|---|---|---|
| `workflow_run` | CREATED、RUNNING、PAUSED、SUCCESS、FAILED、TERMINATED、SUCCESS_DRY_RUN、FAILED_DRY_RUN | 终态更新带前态白名单，迟到 report 不能复活已终止实例 |
| `workflow_node_run` | READY、WAITING_DEPENDENCY、RUNNING、SUCCESS、FAILED、SKIPPED | 同节点重试通过 `run_seq` 保留历史 |
| Edge | SUCCESS、FAILURE、CONDITION、ALWAYS | CONDITION 基于上游 payload/output；空表达式等价 true，应避免误配 |
| Join | ALL、ANY、N_OF | 默认 ALL；N_OF 必须满足合法 threshold |

失败节点无法满足的 SUCCESS 下游会级联标记 `SKIPPED`，避免 ALL join 永久等待。并行分支会同时出现在
`current_node_code` 活跃集合中；只有真实创建了 READY/RUNNING 运行态的节点才进入该集合。

### 3.7 事务、一致性和幂等边界

| 风险 | 当前机制 |
|---|---|
| 触发记录成功但消息丢失 | Trigger 事务性 Outbox，Relay 重启后继续发送 |
| 实例创建和派发长事务争锁 | Launch 拆为 T1 准备事务与 T2 派发事务 |
| task 已落库但 Kafka 未发送 | task/partition/outbox 在 T2 同事务提交 |
| Kafka 重复投递 | request dedup、task claim、Outbox 事件键和数据库唯一约束共同兜底 |
| 两个上游并发触发同一 join | 节点最新运行态行锁 + 唯一约束/CAS 防重复激活 |
| Worker 失联 | claim lease/renew；过期后回收重派 |
| 迟到结果覆盖终态 | task/partition/workflow 状态 CAS 与允许前态白名单 |
| 重跑污染旧结果 | 新 `run_attempt`、父实例引用、配置和结果策略快照 |

### 3.8 租户边界

- 定义、触发、实例、分片、任务、Kafka payload 和 Worker HTTP 请求全程携带 `tenantId`；
- Workflow 与 JOB 引用只能在同租户下解析，禁止跨租户 DAG；
- SDK Worker 不连接平台数据库，只通过 Kafka 和内部 HTTP 执行 claim/renew/report；
- Worker 可以访问执行所需的业务库或对象存储，但不能直接修改 `job_instance/workflow_run/job_task` 等核心状态；
- Console 查询和操作按租户权限过滤，高危的终止、补偿、重放另有 RBAC 与审计。

---

## 4. 三种依赖的最小配置

### 4.1 单上游 — `JOB_A → JOB_B`

```sql
-- 1) workflow_definition
INSERT INTO batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version, enabled)
VALUES ('myten', 'WF_A_THEN_B', 'A 完成后跑 B', 'DAG', 1, true);

-- 2) 4 个节点：START / JOB_A / JOB_B / END
INSERT INTO batch.workflow_node (workflow_definition_id, node_code, node_type, related_job_code, node_order, enabled)
SELECT id, 'START', 'START', NULL, 0, true FROM batch.workflow_definition WHERE workflow_code='WF_A_THEN_B';

INSERT INTO batch.workflow_node (workflow_definition_id, node_code, node_type, related_job_code, node_order, enabled)
SELECT id, 'JOB_A', 'JOB', 'job_a',  1, true FROM batch.workflow_definition WHERE workflow_code='WF_A_THEN_B';

INSERT INTO batch.workflow_node (workflow_definition_id, node_code, node_type, related_job_code, node_order, enabled)
SELECT id, 'JOB_B', 'JOB', 'job_b',  2, true FROM batch.workflow_definition WHERE workflow_code='WF_A_THEN_B';

INSERT INTO batch.workflow_node (workflow_definition_id, node_code, node_type, related_job_code, node_order, enabled)
SELECT id, 'END',   'END', NULL, 3, true FROM batch.workflow_definition WHERE workflow_code='WF_A_THEN_B';

-- 3) 3 条边：START → JOB_A → JOB_B → END
INSERT INTO batch.workflow_edge (workflow_definition_id, from_node_code, to_node_code, edge_type, enabled)
SELECT id, 'START', 'JOB_A', 'ALWAYS',  true FROM batch.workflow_definition WHERE workflow_code='WF_A_THEN_B';

INSERT INTO batch.workflow_edge (workflow_definition_id, from_node_code, to_node_code, edge_type, enabled)
SELECT id, 'JOB_A', 'JOB_B', 'SUCCESS', true FROM batch.workflow_definition WHERE workflow_code='WF_A_THEN_B';

INSERT INTO batch.workflow_edge (workflow_definition_id, from_node_code, to_node_code, edge_type, enabled)
SELECT id, 'JOB_B', 'END',   'SUCCESS', true FROM batch.workflow_definition WHERE workflow_code='WF_A_THEN_B';
```

A 失败时 B 不跑，整个 workflow 走到 JOB_A FAILED 终止。

### 4.2 多上游全部成功 — A、B、C 都成功才跑 D

关键是在 `JOIN_GATE` 节点的 `node_params` 配 `joinMode: ALL`：

```sql
-- GATEWAY 节点
INSERT INTO batch.workflow_node (workflow_definition_id, node_code, node_type, node_params, node_order, enabled)
SELECT id, 'JOIN_GATE', 'GATEWAY',
       '{"joinMode":"ALL"}'::jsonb,
       4, true
FROM batch.workflow_definition WHERE workflow_code='WF_ALL_THEN_D';

-- 三条入边
INSERT INTO batch.workflow_edge ... VALUES
  (..., 'JOB_A', 'JOIN_GATE', 'SUCCESS', true),
  (..., 'JOB_B', 'JOIN_GATE', 'SUCCESS', true),
  (..., 'JOB_C', 'JOIN_GATE', 'SUCCESS', true),
  (..., 'JOIN_GATE', 'JOB_D', 'ALWAYS', true);
```

> **小问题**：GATEWAY 节点不配 `joinMode` 时 `DefaultWorkflowDagService` 默认按 `ALL` 处理（保守，等齐所有上游）。所以"全部成功"其实可以省略 `node_params`，但**显式写出来更可读**。

### 4.3 多上游 N 个成功 — 3 个里 2 个就触发

```sql
INSERT INTO batch.workflow_node (workflow_definition_id, node_code, node_type, node_params, node_order, enabled)
SELECT id, 'JOIN_2OF3', 'GATEWAY',
       '{"joinMode":"N_OF","joinThreshold":2}'::jsonb,
       4, true
FROM batch.workflow_definition WHERE workflow_code='WF_2OF3';
```

`joinThreshold` 含义：上游入边里至少 N 条 fire 就触发下游。
- `joinThreshold=1` ≡ `joinMode=ANY`
- `joinThreshold=入度` ≡ `joinMode=ALL`

### 4.4 任一成功就跑 — `joinMode=ANY`

`{"joinMode":"ANY"}`，等价 `N_OF`+`joinThreshold=1`。
线上例子：`default-tenant/wf_probe_gateway` MERGE 节点就是这样配的。

---

## 5. 边的语义

每条 `workflow_edge` 决定"上游怎样下游才走"：

| edge_type | 触发条件 | 典型用法 |
|---|---|---|
| `SUCCESS` | 上游 node_status = SUCCESS | 主流程串行 |
| `FAILURE` | 上游 FAILED | 错误分支：发告警 / 跑补偿作业 / 跳过下游 |
| `CONDITION` | 上游 SUCCESS **且** `condition_expr` 评估为 true（基于 sourcePayload）| 业务条件分支：走 A 还是走 B |
| `ALWAYS` | 上游进入终态（不管成败）就走 | START→FORK 等无条件流转 |

### 5.1 condition_expr 表达式语法

`WorkflowConditionEvaluator` 支持的最小子集：

```text
逻辑：&& / ||  / !       （也支持大写 AND / OR）
比较：== != > >= < <=
成员：in / not in / contains / startsWith / endsWith
括号：( )
变量：直接写 sourcePayload 里的 key（深路径用 a.b.c）
字面量：数字、'字符串'、布尔
```

实例：

```sql
-- 上游成功且 amount > 1000 才走这条边
INSERT INTO batch.workflow_edge ... VALUES
  (..., 'CALC_TOTAL', 'NOTIFY_BIG', 'CONDITION', 'amount > 1000', true);

-- 名单命中
... 'CONDITION', "kycLevel in ('HIGH','MEDIUM')"

-- 复合
... 'CONDITION', "amount >= 10000 && currency == 'CNY' && !suspect"
```

> **空表达式 = always true**（CONDITION 边但没填 `condition_expr` ≈ ALWAYS 边）。
> sourcePayload 来自上游节点输出 + workflow_node.node_params 合并；非 Map 类型默认为空。

---

## 6. 节点类型选哪个

### `JOB`（最常用）— 跨节点串子作业

```
related_job_code = 'job_a'   →  独立子 job_instance
```

每次执行会**新建一个独立的 job_instance**（带回指 `_parentNodeCode/_parentVirtualTaskId/_parentWorkflowRunId`），跑完再回写父 workflow_node_run 状态。子作业可以是任何 IMPORT/EXPORT/DISPATCH/WORKFLOW。

### `TASK` — workflow 内嵌 pipeline step

```
related_pipeline_code = 'export_settlement_pipeline'
```

不开新 job_instance，而是 workflow 内执行一段 pipeline。一般用于轻量步骤（不需要独立调度/quota 计费）。

### `FILE_STEP` — 文件相关步骤

针对 dispatch / import 中的 file 中间环节（如等待文件到达）。

### `GATEWAY` — 路由 / 汇聚

- 入度=1 时通常是 fork（一进多出，配多条出边）
- 入度≥2 时是 join，按 `joinMode` 等待

### `WAIT` — 等待外部条件（谨慎使用）

目标语义是等待文件到达、HTTP 条件、Kafka offset 或业务库信号。后端已有 SensorPolicy、轮询调度器、
状态机和静态校验，但 ADR-028 仍为 Proposed，完整节点初始化和生产级 E2E 尚未形成稳定证据。
在验收完成前，生产流程优先使用文件等齐、readiness 或外部事件触发，不把 WAIT 当作已承诺能力。

### `START` / `END` — 边界

每个 workflow 必须有且仅有一个 START 和至少一个 END。

---

## 7. 实战例子（DB 里能看到的）

### 7.1 串行链 — `tc/TC_WF_RISK_PIPELINE`

```mermaid
flowchart LR
  S[START] --> I[NODE_IMPORT<br/>JOB→TC_IMPORT_RISK_SCORE]
  I --> E[NODE_EXPORT<br/>JOB→TC_EXPORT_RISK_ALERT]
  E --> D[NODE_DISPATCH<br/>JOB→TC_DISPATCH_REVIEW]
  D --> END[END]
```

边全是 `SUCCESS`，3 个 JOB 节点串行依赖。

### 7.2 Fork-Join — `default-tenant/wf_probe_gateway`

```mermaid
flowchart LR
  S[START] -- ALWAYS --> F{FORK<br/>GATEWAY}
  F -- ALWAYS --> A[BRANCH_A<br/>TASK]
  F -- ALWAYS --> B[BRANCH_B<br/>TASK]
  A -- SUCCESS --> M{MERGE<br/>GATEWAY<br/>joinMode=ANY}
  B -- SUCCESS --> M
  M -- ALWAYS --> END[END]
```

两个分支并行，任一成功 MERGE 就 fire。

### 7.3 GATEWAY ALL + 备路径 — `tc/TC_WF_GATEWAY_ALL`（docs/agent-baseline.md 2026-04-22 提及）

3 个 branch 都成功才汇聚；带 `FAILURE` / `CONDITION` 边到 fallback 子路径。覆盖了 `WorkflowJoinMode` 全部三个值 + `WorkflowEdgeType` 全部四个值的语义。

---

## 8. join 何时 fire — 代码层规则

`DefaultWorkflowDagService.shouldFireJoin`：

```java
return switch (joinRule.joinMode()) {
  case ALL  -> matchedCount == incomingEdgeCount;     // 全到齐
  case ANY  -> matchedCount >= 1;                     // 任一即触发
  case N_OF -> matchedCount >= joinRule.joinThreshold(); // ≥ 阈值
};
```

`matchedCount` = "已 fire 的入边数"（按 edge_type 评估：SUCCESS 边等上游 SUCCESS、CONDITION 边再叠加表达式判断）。

### 默认值（保守语义）

- `joinMode` 缺失或非法字符串 → 走 `ALL`
- `joinThreshold` 缺失或 ≤ 0 → 默认 `incomingEdgeCount`（等价 ALL）

> 这两个默认值是有意为之：解析失败时不要"提前触发"导致漏数据。

---

## 9. 不支持或尚未稳定验收的场景

| 场景 | 现状 | 替代 |
|---|---|---|
| **跨 workflow 依赖**（workflow_A 依赖 workflow_B） | 分两种形态：① **同步嵌套**（支持）——JOB 节点 `related_job_code` 指向一个 `job_type=WORKFLOW` 的 job，父 workflow 把子 workflow 作为子 `job_instance` 拉起并等待其终态（见 §6 `JOB` 和环检测说明）；② **异步解耦触发**（不内建）——A 完成后让独立调度的 B 自动启动 | 同步依赖直接用 JOB 节点嵌套；异步解耦让 workflow_A 末节点写一个事件，workflow_B 配事件触发入口 |
| **跨 tenant 依赖** | 不允许。`related_job_code` 在同 `tenant_id` 下查 `job_definition` | 设计如此（多租户隔离）。需要的话拆成两个 workflow 通过事件桥接 |
| **依赖外部系统就绪** | WAIT Sensor 后端组件已存在，但 ADR-028 仍为 Proposed，完整派发接线与稳定 E2E 尚未验收 | 当前生产流程优先使用文件等齐/readiness 或由外部系统调用触发入口；WAIT 验收后再开放 |
| **环 / 自循环** | 强制 DAG，三道防线：① 单 workflow 内的边环，配置期 `WorkflowDagValidator.validate` Kahn 拒绝；② 跨 workflow 嵌套环（A 的 JOB 节点→B，B 又→A，或自引用），**配置期** `WorkflowDagValidator.validateNoCrossWorkflowCycle` 在 `fullUpdate` 保存时沿「JOB→WORKFLOW」展开 workflow 图 DFS 检测，命中抛 `error.workflow.dag.cross_workflow_cycle_detected`；③ 同样的跨 workflow 嵌套环，**运行期** `ChildJobLaunchSupport` 在拉起子作业前沿 `parent_instance_id` 链上溯检测祖先 job_code，命中抛 `error.workflow.nested_cycle_detected` fail-fast（回退定义漂移/绕过保存校验的情况） | 重试用 `retry_policy`，不要用边或嵌套模拟循环 |
| **动态依赖**（运行时根据数据决定下个节点） | 静态 DAG。能用 CONDITION 边在配置层做有限分支 | 复杂动态分支建议拆成多个 workflow + 事件触发 |

---

## 10. 一些常见配错检查表

跑前过一遍这几条能少遇到问题：

- [ ] **每个 GATEWAY 节点都显式写 `joinMode`**——别让默认值 ALL 把"任一即触发"的意图悄悄改了语义
- [ ] **`joinMode=N_OF` 必须带 `joinThreshold`**，且 `0 < threshold ≤ 入度`
- [ ] **每个非 START 节点都有至少一条入边**——否则 worker 永远等不到触发（昨天 TC_WF_RISK_PIPELINE 就因为缺 `START→NODE_IMPORT` 边导致 workflow 直接跳到 END）
- [ ] **每个非 END 节点都有至少一条出边**——否则成 dead end
- [ ] **JOB 节点的 `related_job_code` 在同 tenant 的 `job_definition` 里能查到 + enabled=true**
- [ ] **CONDITION 边一定要配 `condition_expr`**（空字符串等价 ALWAYS，可能不是你的本意）
- [ ] **不要循环**——A→B→A 启动期校验会拒
- [ ] **`enabled=true`** 别忘了打开

---

## 11. 节点间参数串联(ADR-009 DSL)

### 11.1 解决什么问题

DAG 上游节点(如 SETTLE)产出 `fileId`、`recordCount` 等运行时字段,下游节点(如 DISPATCH)需要这些字段做后续处理。**默认行为**:`mergeUpstreamPartitionOutputs` 自动把同 jobInstance 兄弟分区的 `output_summary` 中 `fileId/fileCode` 等"已知少量字段"塞到下游 payload。**这适合 fileId 这类规约字段**;但**业务字段 / 多分支节点 / 跨节点字段名不同**时不够用——需要 workflow 设计者**显式声明引用**。

ADR-009 引入受限 JSONPath 子集做这种显式引用。

### 11.2 引用语法

`workflow_node.node_params`(JSONB)的 value 支持 `$.xxx` 形式的引用:

| 语法 | 语义 | 例 |
|---|---|---|
| `$.nodes.<nodeCode>.output.<key>` | 引用同 workflow_run 内某节点 output 的某字段 | `$.nodes.SETTLE.output.fileId` |
| `$.nodes.<nodeCode>.output.<a>.<b>` | 嵌套字段下钻 | `$.nodes.SETTLE.output.summary.totalAmount` |
| `$.workflowRun.<key>` | workflow 级共享字段 | `$.workflowRun.bizDate` |

**不支持**:通配符 `*`、过滤 `[?]`、函数 `length()`、表达式 `$ + 1`。

### 11.3 例子

```json
{
  "fileId": "$.nodes.SETTLE.output.fileId",
  "channelCode": "ftp_outbound",
  "_meta": {
    "expectedSizeBytes": "$.nodes.SETTLE.output.size"
  },
  "bizDate": "$.workflowRun.bizDate"
}
```

只把 `$.xxx` 形式的 String 替换为实际值;非 String / 非 `$.` 开头的字段原样保留。嵌套 Map / List 中的引用递归解析。

### 11.4 fail 模式

| 场景 | 行为 |
|---|---|
| 上游节点未跑(output 整体 null) | resolver 返回 null,下游业务自己 null 检查 |
| 上游节点已跑但缺引用的 key | resolver 返回 null,同上 |
| 引用未知 nodeCode(typo / 节点未在 workflow 定义中) | **fail-fast**:抛 `BizException(WORKFLOW_PARAM_REF_INVALID)`,节点拒绝启动 |
| 路径语法非法(不匹配 `$.nodes.X.output.Y` 也不匹配 `$.workflowRun.Z`) | **fail-fast**:同上 |

### 11.5 解析时机

`DefaultWorkflowNodeDispatchService.mergeNodeParams` 在派发下游 task payload 时调用 `WorkflowParamResolver.resolve(parsed, workflowRunContext)`。WorkflowRunContext 由 `loadWorkflowRunContext(workflowRun)` 在派发前一次性 select `workflow_node_run` 表所有兄弟节点的 `output JSONB` 反序列化构造,不持久化。**重试场景**:同 nodeCode 多次执行取最新 run_seq 的 output。

### 11.6 实现位置

| 文件 | 角色 |
|---|---|
| `WorkflowParamResolver.java` | 解析器(160 行,10 单测) |
| `WorkflowRunContext.java` | 上下文接口 |
| `DefaultWorkflowNodeDispatchService.java:mergeNodeParams` | 集成点(line 723-) |
| Flyway `V72__add_workflow_node_run_output.sql` | output JSONB 列 |

---

## 12. 进一步阅读

- [`system-flow-overview.md`](./system-flow-overview.md) — 系统总流程，看 workflow 在整体架构中的位置
- [`core-model.md`](./core-model.md) — workflow_run / workflow_node_run 等运行态实体
- [`../runbook/worker-stage-coverage.md`](../runbook/worker-stage-coverage.md) — TC_WF_RISK_PIPELINE 的真实跑通过程（含 sourcePayload 继承的问题、`buildChildLaunchRequest` 怎么把 `node_params` 透到子作业）
- 源码：
  - `batch-orchestrator/.../service/DefaultWorkflowDagService.java`（DAG 计算 + join 规则）
  - `batch-orchestrator/.../service/WorkflowConditionEvaluator.java`（CONDITION 表达式解析）
  - `batch-orchestrator/.../service/DefaultWorkflowNodeDispatchService.java`（节点派发：JOB / TASK 两条路径）
  - `batch-common/.../enums/WorkflowJoinMode.java` / `WorkflowEdgeType.java` / `WorkflowNodeType.java`
