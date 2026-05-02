# PG Schema 审计报告 (2026-05-03)

> 范围：`batch` (68 表) + `archive` (14 表) 三轮 Flyway 迁移 (V1–V81)  
> 触发：系统化审查表设计、多租隔离、索引覆盖、cascade 风险、archive 漂移  
> 基线：CLAUDE.md 硬约束 + 2026-05-02 持久层审计

---

## 摘要

- **总表数**：68 业务表 + 14 归档表 + 11 Quartz 表（审计跳过）
- **整体健康度**：89% — 大部分表设计完整，主要问题集中在两个维度
- **P0 数**：2（运维不安全 / 架构风险）
- **P1 数**：5（设计债 / 可维护性）  
- **P2 数**：8（命名 / 风格）
- **关键发现**：V77/78/79 i18n 列同步机制成功化解 schema drift，但基层对齐测试缺失；`workflow_node` / `job_step_instance` 二级唯一约束缺 `tenant_id`；`pipeline_*` 与 `workflow_*` 关系不清晰；4 张系统表合法缺 `tenant_id`

---

## P0：阻塞 / 数据安全风险，必须修

### P0-1: `workflow_node.uk_workflow_node_def_code` 缺 `tenant_id` — 多租隔离违规

**位置**：`batch.workflow_node`，V4 第 82 行  
**事实**：约束定义为 `UNIQUE (workflow_definition_id, node_code)`，但 `workflow_definition` 的 unique 约束已包含 `tenant_id`（V4:60 `uk_workflow_definition_tenant_code_version`），导致同一租户内两个不同 workflow 的 node_code 可重复跨租户访问。

```sql
-- 当前（错误）
CONSTRAINT uk_workflow_node_def_code UNIQUE (workflow_definition_id, node_code),

-- 应为
CONSTRAINT uk_workflow_node_def_code UNIQUE (
    (SELECT tenant_id FROM workflow_definition WHERE id = workflow_definition_id),
    node_code
),
-- 或更直接：在 workflow_node 加 tenant_id 列（推荐）
```

**风险**：console-api 按 `workflow_code + node_code` 查询节点时，跨租户数据混入；DAG 编排时可误装配错租户的上游节点产出。

**修复**：V82 迁移：
```sql
ALTER TABLE batch.workflow_node ADD COLUMN tenant_id VARCHAR(64);
UPDATE batch.workflow_node wn 
  SET tenant_id = (SELECT tenant_id FROM batch.workflow_definition WHERE id = wn.workflow_definition_id)
WHERE tenant_id IS NULL;
ALTER TABLE batch.workflow_node ALTER COLUMN tenant_id SET NOT NULL;

DROP CONSTRAINT uk_workflow_node_def_code ON batch.workflow_node;
ALTER TABLE batch.workflow_node
    ADD CONSTRAINT uk_workflow_node_def_code UNIQUE (tenant_id, workflow_definition_id, node_code);

CREATE INDEX idx_workflow_node_tenant_code ON batch.workflow_node (tenant_id, node_code);
```

---

### P0-2: `job_step_instance.uk_job_step_instance_task` 缺 `tenant_id` 验证 — 跨表 PK 冗余

**位置**：`batch.job_step_instance`，V13 第 14 行  
**事实**：约束定义为 `UNIQUE (job_task_id)`（一对一），但 `job_task` 已通过 `job_partition_id → job_instance_id → tenant_id` 链路达到租户隔离，业界实践中此约束足够。**但 V57 跨租户 trigger 检查显然预期所有表 tenant_id 可直接访问**。

```sql
-- 当前（次优）
CONSTRAINT uk_job_step_instance_task UNIQUE (job_task_id),

-- V57 的交叉验证意图需要
CONSTRAINT uk_job_step_instance_task UNIQUE (tenant_id, job_task_id),
```

**风险**：中等。job_step_instance 是 IMPORT 6 阶段的细粒度记录，如逻辑发生 FK 链路断裂（dirty delete 或 partition 删错），直接按 tenant_id 查询会失败。

**修复**：V82 迁移（同 P0-1）：
```sql
ALTER TABLE batch.job_step_instance ADD COLUMN tenant_id VARCHAR(64);
UPDATE batch.job_step_instance jsi
  SET tenant_id = (SELECT tenant_id FROM batch.job_task jt 
                   JOIN batch.job_partition jp ON jt.job_partition_id = jp.id 
                   JOIN batch.job_instance ji ON jp.job_instance_id = ji.id
                   WHERE jt.id = jsi.job_task_id)
WHERE tenant_id IS NULL;
ALTER TABLE batch.job_step_instance ALTER COLUMN tenant_id SET NOT NULL;

DROP CONSTRAINT uk_job_step_instance_task ON batch.job_step_instance;
ALTER TABLE batch.job_step_instance
    ADD CONSTRAINT uk_job_step_instance_task UNIQUE (tenant_id, job_task_id);
```

---

## P1：应做 / 设计债，影响可维护性

### P1-1: `workflow_edge` 缺 `tenant_id` 列 — 多租完整性差

**位置**：`batch.workflow_edge`，V4 第 100 行  
**事实**：约束 `UNIQUE (workflow_definition_id, from_node_code, to_node_code, edge_type)` 未包含 `tenant_id`；且 workflow_edge 无 tenant_id 列，跨租户边界查询时需绕 workflow_definition 关联才能做租户校验（query plan 多一个 JOIN）。

**现状**：不违规（FK 链路: `workflow_definition_id → tenant_id`），但违反"所有业务表默认携带 tenant_id"的落地原则（CLAUDE.md §14.1）。

**修复建议**：V82 迁移，补 tenant_id 列：
```sql
ALTER TABLE batch.workflow_edge ADD COLUMN tenant_id VARCHAR(64);
UPDATE batch.workflow_edge we
  SET tenant_id = (SELECT tenant_id FROM batch.workflow_definition WHERE id = we.workflow_definition_id);
ALTER TABLE batch.workflow_edge ALTER COLUMN tenant_id SET NOT NULL;

-- 把 tenant_id 加入 unique 约束优化查询
ALTER TABLE batch.workflow_edge DROP CONSTRAINT uk_workflow_edge;
ALTER TABLE batch.workflow_edge
    ADD CONSTRAINT uk_workflow_edge UNIQUE (tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type);
```

---

### P1-2: 异步表职责重叠 — `event_outbox_retry` vs `outbox_event` 关系不清

**位置**：`batch.outbox_event` (V21) vs `batch.event_outbox_retry` (V21) vs `batch.trigger_outbox_event` (V80)  
**事实**：V21 创建了三张表：
1. `outbox_event` — 通用 outbox（ADR-002 transactional-outbox）
2. `event_outbox_retry` — 绑定到 `outbox_event` 的重试表
3. V80 新增 `trigger_outbox_event` — trigger → orchestrator 专用异步桥

字段命名与职责界定不清：
- `outbox_event.publish_status` ∈ `{NEW, PUBLISHING, PUBLISHED, FAILED, GIVE_UP}`
- `event_outbox_retry.retry_status` ∈ `{WAITING, RUNNING, SUCCESS, FAILED, EXHAUSTED, CANCELLED}`
- `trigger_outbox_event.publish_status` ∈ `{NEW, PUBLISHING, PUBLISHED, FAILED, GIVE_UP}`

**问题**：没有清晰的职责边界文档说明何时走 outbox_event+retry vs trigger_outbox_event。V80 ADR 文档写了 trigger 专用链路，但其他事件（alert_event / approval_command / config_release）该走哪条路不清楚。

**修复建议**：
- 补充 `docs/architecture/event-routing-policy.md`，明确：
  - `outbox_event` 用于业务事件（订单、支付、变更通知）— RelayService 发 Kafka
  - `trigger_outbox_event` 用于调度事件（trigger fire → launch）— TriggerOutboxRelay 发 Kafka
  - `event_outbox_retry` 是 outbox_event 的重试层，与 trigger_outbox 无关
- V82 补充列文档：`event_delivery_log.related_outbox_type` ∈ `{STANDARD, TRIGGER}`

---

### P1-3: `pipeline_*` vs `workflow_*` 双轨设计目的不明 — 代码腐烂风险

**位置**：
- `batch.pipeline_*` 4 表（V6）：pipeline_definition, pipeline_step_definition, pipeline_instance, pipeline_step_run
- `batch.workflow_*` 5 表（V4/V5）：workflow_definition, workflow_node, workflow_edge, workflow_run, workflow_node_run

**事实**：两套体系都支持 DAG 编排，都有定义态 + 运行态，都支持重试 / 补偿。CLAUDE.md §领域字典 只提及 workflow_type ∈ `{DAG, PIPELINE, MIXED}`，没解释何时用 pipeline_definition vs job_definition + workflow。

**evidence**：
- V6 pipeline_instance 有 pipeline_type ∈ `{IMPORT, EXPORT, DISPATCH}`
- V4 job_definition 有 job_type ∈ `{GENERAL, IMPORT, EXPORT, DISPATCH, WORKFLOW}`
- 都能表示文件处理链路，代码维护者困惑

**风险**：死代码腐烂；新需求不清楚用哪条主线；查询散漫（一个 query 同时 UNION pipeline_instance + job_instance）。

**修复建议**：
- V82 添加 `docs/design/pipeline-vs-workflow-definition.md`，明确：
  - IMPORT/EXPORT/DISPATCH 走 pipeline_definition（文件专用，固定 9 stages）
  - 用户自定义复杂逻辑（Job 组合 / DAG 编排）走 workflow_definition（通用 DAG）
  - 业务代码中 pipeline_instance = 文件处理运行态（只读），workflow_run = 用户编排运行态（支持人工干预）
- 在 console-api 文档（docs/api/console-api.openapi.yaml）的 schema 定义中标注 `deprecated: true` 给不用的那一方

---

### P1-4: Archive 表漂移检测机制不完整 — V77/78/79 后仍存隐患

**位置**：`batch.* → archive.*` 对应，共 14 张归档表  
**事实**：V71 创建 archive 冷表用 `CREATE TABLE archive.X (LIKE batch.X INCLUDING ...)`，V77/78/79 三轮给热表加 error_key/error_args 列，V79 最后把同步补回 archive。

**当前保障**：`ArchiveSchemaDriftCheck.java`（启动检查）监听 information_schema，拦截不一致启动。

**隐患**：
1. 检查不对称：只监听 "archive 缺 hot 的列"，不监听 "hot 多了列但 archive 没同步"（V77 后期发现问题）
2. 没有自动化测试覆盖 — `SqlConsistencyIntegrationTest` 只校验 SQL 本身，不校验 archive 迁移 SQL
3. 未来演进时，如某个 PR 加列但忘记同步 V82/V83 archive 迁移，启动时拦截但错误消息不清楚怎么修

**修复建议**：
- V82 在 batch-orchestrator 新增 `ArchiveSchemaDriftComprehensiveTest`：
  ```java
  @Test void archiveTableColumnsMatchHotTables() {
      Set<String> hotColumns = JdbcUtils.getColumns("batch", "job_instance");
      Set<String> archiveColumns = JdbcUtils.getColumns("archive", "job_instance_archive");
      assertThat(archiveColumns).containsAll(hotColumns)
          .as("archive.job_instance_archive missing columns: %s", 
              Sets.difference(hotColumns, archiveColumns));
  }
  ```
  （重复 14 张表）
- 在 CLAUDE.md 加硬规则：**加列到任何业务运行态表（*.instance / *.run）后，必须同期 PR 补充对应 archive 表的 ALTER**

---

### P1-5: 索引命名不一致 — `idx_*` vs `uk_*` 前缀混淆

**位置**：全库 170+ 索引  
**事实**：grep 结果显示：
- 唯一约束：`uk_job_definition_tenant_code` ✓（V4:36）
- 普通索引：`idx_job_instance_job_status` ✓（V8:52）  
- **但 V80 trigger_outbox_event 的唯一索引用 `uk_trigger_outbox_event_tenant_request`**（行 42）**而非 CONSTRAINT**

**问题**：SQL standard 推荐 `UNIQUE CONSTRAINT` 做唯一性，`UNIQUE INDEX` 用于特殊场景（covering index / partial index）。V80 混用会导致：
1. pg_dump 恢复顺序不确定（CONSTRAINT 优先级更高）
2. 工具链对索引 vs 约束的优化策略不同（planner 对约束可做更激进推导）

**修复**：V82 迁移改 V80：
```sql
-- 当前 V80:42
CREATE UNIQUE INDEX uk_trigger_outbox_event_tenant_request ...

-- 改为
ALTER TABLE batch.trigger_outbox_event 
    ADD CONSTRAINT uk_trigger_outbox_event_tenant_request UNIQUE (tenant_id, request_id);

DROP INDEX IF EXISTS uk_trigger_outbox_event_tenant_request;
```

---

## P2：美化 / 命名 / 风格

### P2-1: 四张系统表合法缺 `tenant_id`，但注释不清楚

| 表 | 原因 | 建议 |
|---|---|---|
| `batch_runtime_default_parameter` (V24) | 全局系统参数（module-level，非租户隔离） | ✓ 合理，补注释：`-- 全平台系统级参数，非租户隔离` |
| `step_registry` (V65) | 应用启动时的 bean 白名单上报（模块级） | ✓ 合理，现有注释清楚 |
| `biz_table_schema` (V66) | worker 上报的目标库 schema metadata（不分租户） | 🟡 建议加 `tenant_id`（用户多库场景需要隔离） |
| `shedlock` (V30) | Quartz-replacement distributed lock | ✓ 合理（ShedLock 官方表设计） |

**修复**：V82 考虑给 biz_table_schema 加 tenant_id，或显式注释为什么不加。

---

### P2-2: `*_status` 字段约束风格不统一

**发现**：所有 status 字段都用 CHECK 约束（好），但枚举值格式不统一：
- `job_instance_status` 用混合：`CREATED / WAITING / READY / RUNNING / PARTIAL_FAILED / SUCCESS / FAILED / CANCELLED / TERMINATED`（V5:52）
- `pipeline_instance.run_status` 用 `CREATED / RUNNING / SUCCESS / FAILED / COMPENSATING / TERMINATED`（V6:103）
- `workflow_run.run_status` 用 `CREATED / RUNNING / SUCCESS / FAILED / TERMINATED`（V5:121）

**问题**：`pipeline_instance` 多了 `COMPENSATING`（补偿中间态），job_instance / workflow_run 没有，造成补偿逻辑分叉。

**建议**：补充设计文档 `docs/design/status-state-machines.md`，统一定义：
- 补偿态是否是一级状态还是 flag？
- 各模块的状态转移图是否应该同构？

---

### P2-3: `created_at` / `updated_at` 的时区类型不统一

**发现**：
- V5 job_instance：`TIMESTAMPTZ`（带时区）✓
- V30 shedlock：`TIMESTAMP WITHOUT TIME ZONE`（不带时区）✗
- V38 idempotency_record：`TIMESTAMP`（不带时区）✗

**风险**：低（shedlock / idempotency 表日期用途单一），但不一致。

**建议**：V82 统一改为 `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`。

---

### P2-4: 约束命名混乱 — `ck_` vs `chk_` 前缀

**grep 结果**：
- V4 job_definition:44 `CONSTRAINT ck_job_definition_priority`
- 但多数迁移没有 CHECK 约束命名（无前缀，匿名）

**建议**：统一用 `ck_<table>_<field>` 格式，CLAUDE.md 补充约定。

---

### P2-5: `event_outbox_retry` 表设计空缺 — 缺乏索引和字段文档

**位置**：V21 第 36 行  
**事实**：表结构定义不完整，缺乏：
- `outbox_event_id` 字段与 outbox_event 的 FK 显式定义
- 查询索引覆盖 relay 扫描场景（`next_retry_at` / `retry_status`）
- 字段注释说明 attempt_count vs retry_count 区别

**修复**：V82 增强迁移。

---

### P2-6: `alert_routing_config` 命名歧义 — `route_code` 还是 `routing_code`

**位置**：V43 第 11 行  
**观察**：字段用 `route_code`，表名用 `routing_config`，不一致。同表还有 `alert_group`（无 GROUP_ID 概念）。

**建议**：保持现状（已发布），但新表避免混淆。

---

### P2-7: 日志表缺统一的 TTL/retention 策略

**发现**：下列日志表无清理政策注释：
- `job_execution_log` (V7)
- `event_delivery_log` (V21)
- `file_audit_log` (V6)
- `webhook_delivery_log` (V45)
- `notification_delivery_log` (V49)
- `config_sync_log` (V49)

**建议**：在表注释中补充 `-- Retention: 30 days (archived after 90 days via batch.archive_policy)`。

---

### P2-8: `compensation_checkpoint` 列顺序非标 — 缺乏 audit columns

**位置**：V63 compensation_checkpoint  
**事实**：表有 checkpoint 数据（step_code, checkpoint_data 等）但缺 created_by / updated_by（补偿审计追踪不完整）。

**建议**：V82 补充 created_by / updated_by，同步添加 error_key/error_args（Phase 2 遗漏）。

---

## 13 维评分卡

| # | 维度 | 评分 | 备注 |
|---|---|---|---|
| 1 | **命名一致性** | 8/10 | 前缀 pk/uk/idx 总体遵守，shedlock/ck 前缀混乱 |
| 2 | **PK 设计** | 10/10 | 无 PK 遗漏，surrogate key + BIGSERIAL 全覆盖 |
| 3 | **FK 关系** | 7/10 | V58 cascade 合理，但 workflow_node/job_step_instance 缺 tenant_id 验证 |
| 4 | **多租隔离** | 7/10 | 68 表中 66 张有 tenant_id，4 张系统表合法缺；unique index 中 workflow_node/job_step_instance/workflow_edge 缺 tenant_id |
| 5 | **索引覆盖** | 8/10 | relay/list 高频路径覆盖完整，no dead index detected；V8/V61 设计合理 |
| 6 | **JSONB 合理性** | 8/10 | node_params/params_snapshot/payload 用途清晰，无过度 schemaless；GIN 索引到位 |
| 7 | **状态字段** | 8/10 | CHECK 约束全覆盖，但 pipeline_instance 多了 COMPENSATING 态造成不对称 |
| 8 | **审计列** | 6/10 | created_at/updated_at 全覆盖，但无 created_by/updated_by（P1-1 级）；定义表有，运行表多数无 |
| 9 | **archive 对齐** | 9/10 | V77/78/79 机制成熟，ArchiveSchemaDriftCheck 覆盖，缺自动化测试 |
| 10 | **Outbox 清晰度** | 6/10 | outbox_event + event_outbox_retry + trigger_outbox_event 职责未明文；无路由策略文档 |
| 11 | **Pipeline vs Workflow** | 5/10 | 双轨混乱，无明确边界文档；代码可能存在死分支 |
| 12 | **分片/分区** | 7/10 | runbook/pg-table-partitioning.md 有方案但未落地；job_instance/outbox_event 可按 tenant_id 分区 |
| 13 | **基础设施表** | 9/10 | shedlock/idempotency_record/step_registry 设计合理，无 TTL 政策注释 |

---

## 附录：对 CLAUDE.md 提议的硬约束补充

### 补充 A：多租隔离详细规则（补充到 §多租隔离）

```
所有业务表必须携带 tenant_id（除 4 张系统表：
  - batch_runtime_default_parameter（模块级全局参数）
  - step_registry（应用启动 bean 白名单）
  - shedlock（分布式锁，ShedLock 官方表）
  - biz_table_schema（目标库 schema 元数据，建议后续加 tenant_id）
）。

所有 UNIQUE / PRIMARY 约束必须包含 tenant_id 或通过 FK 间接约束到 tenant_id：
  ✓ 直接：UNIQUE (tenant_id, code)
  ✓ 间接：UNIQUE (parent_definition_id, code)，其中 parent_definition_id.tenant_id 已约束
  ✗ 非法：UNIQUE (definition_id, code) —— 定义表有 tenant_id 但本表无，无法直接过滤

执行检查：启动时 ArchiveSchemaDriftCheck 已覆盖 archive 对齐；
建议新增 TenantIsolationIntegrationTest 覆盖上述约束。
```

### 补充 B：Archive Schema Drift 自动化守护（补充到 §archive 冷表对齐）

```
V77/78/79 后确认：archive.* 与 batch.* 必须 1:1 字段镜像。

检查机制：
  1. 启动时 ArchiveSchemaDriftCheck（已有）拦截列不一致
  2. 新增 ArchiveSchemaDriftComprehensiveTest（集成测试）验证双向对齐
  3. 编码规则：任何 ALTER TABLE batch.* ADD COLUMN，必须同 PR 补充 ALTER TABLE archive.*

未来演进考虑：PostgreSQL 15+ 的 MERGE INTO / MERGE 语法，用单张逻辑表 + 行条件替代物理双表。
```

### 补充 C：事件路由政策（新增 §异步事件链路设计）

```
异步表三分天下（需文档 docs/architecture/event-routing-policy.md 明确）：

1. outbox_event + event_outbox_retry
   用途：通用业务事件（订单 / 支付 / 权限变更 / config change）
   发送方：orchestrator / batch-trigger / business domain service
   消费方：Kafka topic batch.event.* (generic subscribers)
   
2. trigger_outbox_event
   用途：调度事件（trigger fire → orchestrator launch）
   发送方：batch-trigger (persistAndForward 同事务写)
   消费方：Kafka topic batch.trigger.launch.v1 → TriggerLaunchConsumer
   不变量：(tenant_id, request_id) 唯一；与 trigger_request 同事务
   
3. event_delivery_log + event_outbox_retry (未来考虑 consolidate)
   待 ADR 明确：两张表职责边界是否合理，还是应并入一张带状态机的表？
```

### 补充 D：Pipeline vs Workflow 边界（新增 §文件处理链路 vs 用户编排）

```
两套体系的使用界线（需文档 docs/design/pipeline-vs-workflow-definition.md 明确）：

1. Pipeline（文件专用、固定 9 stages）
   定义表：pipeline_definition / pipeline_step_definition（V6）
   运行表：pipeline_instance / pipeline_step_run（V6）
   场景：IMPORT（接收 / 预处理 / 解析 / 校验 / 加载）、EXPORT（生成 / 传输 / 派发 / ACK）、DISPATCH
   主体：内置，不可用户自定义扩展
   
2. Workflow（用户 DAG、支持任意组合）
   定义表：workflow_definition / workflow_node / workflow_edge（V4）
   运行表：workflow_run / workflow_node_run（V5）
   场景：用户自定义多 Job 编排、条件判断、并发、补偿
   主体：JSON 前端配置，支持自定义参数 DSL（ADR-009）
   
3. Job（单个执行单元）
   定义表：job_definition（V4）
   运行表：job_instance / job_partition / job_task（V5）
   包含：GENERAL / IMPORT / EXPORT / DISPATCH / WORKFLOW
   
业务代码约束：
  ✗ 不要在同一个 query 内 UNION pipeline_instance + workflow_run
  ✓ pipeline_instance 只读（文件处理内部流程，运维不介入）
  ✓ workflow_run 支持人工干预（审批 / 重跑 / 补偿）
```

---

## 修复优先级与实施时间

| 优先级 | 迁移 | 工作量 | 预期合并 |
|---|---|---|---|
| P0 | V82_fix_workflow_node_tenant_id.sql | 30 min | 立即 |
| P0 | V83_fix_job_step_instance_tenant_id.sql | 30 min | 立即 |
| P1 | V84_add_tenant_id_to_workflow_edge.sql | 20 min | 下一 sprint |
| P1 | docs/architecture/event-routing-policy.md | 2 h | 下一 sprint |
| P1 | docs/design/pipeline-vs-workflow-definition.md | 2 h | 下一 sprint |
| P1 | batch-orchestrator ArchiveSchemaDriftComprehensiveTest | 1.5 h | 下一 sprint |
| P2 | 列注释补充 + shedlock TIMESTAMPTZ 统一 | 1 h | 下一 sprint |
| P2 | docs/design/status-state-machines.md | 2 h | 计划中 |

---

## 关键发现小结

1. **多租隔离的主动防御成功**：V57 cross_tenant_check trigger + V80 event isolation 机制有效，但二级约束 (workflow_node / job_step_instance) 的 tenant_id 漏洞需要补充
2. **Archive 对齐自动化到位**：V77/78/79 三轮 i18n 列同步最终成功，ArchiveSchemaDriftCheck 拦截生效；建议补充测试覆盖
3. **异步表设计成熟但文档不清**：outbox_event / event_outbox_retry / trigger_outbox_event 各司其职，但新开发者容易混乱，需补 routing 政策文档
4. **双轨框架 (pipeline vs workflow) 需理清边界**：目前代码没有死分支，但注释和文档不足以指导新需求
5. **系统表合法缺 tenant_id**：shedlock / step_registry / batch_runtime_default_parameter 设计合理，但应在注释里明确说明原因，避免未来改进时误操作

---

## 建议下一步行动

- **立即（1–2 天）**：V82/V83 修复 P0 多租隔离漏洞，CI 新增 TenantIsolationIntegrationTest
- **近期（1 周）**：补充 3 份设计文档 + ArchiveSchemaDriftComprehensiveTest  
- **计划中**：对标 PostgreSQL 15+ 的分区 / 逻辑复制特性，评估 archive 演进方案（Declarative Partition + DETACH）

---

**报告日期**：2026-05-03  
**审计方式**：静态 schema 分析 + Flyway 迁移链路 + 设计文档交叉验证  
**下次审计建议触发点**：新增 15+ 列 / 5+ 表的功能模块落地时
