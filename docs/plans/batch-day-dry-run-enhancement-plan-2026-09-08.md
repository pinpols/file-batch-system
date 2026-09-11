# 整批量日 Dry-run 增强设计与实施计划

日期：2026-09-08
状态：Implemented（代码与本地自动化验证完成；生产启用前仍需 staging 零副作用验收）
关联：ADR-020（批量日重放）、ADR-026（Dry-run）、ADR-017（结果版本）、ADR-018（跨日依赖）

## 1. 决策摘要

整批量日 Dry-run 不直接复用或扩展正式 `batch_day_instance` 的唯一身份，也不把
`(tenant_id, calendar_code, biz_date)` 改成包含 `dry_run`。正式批量日必须继续保持每个租户、日历、业务日唯一。

推荐在现有 `batch_day_replay_session` / `batch_day_replay_entry` 聚合上增加
`execution_mode=DRY_RUN`，把整日演练建模为一种不可晋升、无业务副作用的受控执行会话：

- session 负责审批、候选物化、进度、限流、取消和审计；
- entry 负责每个 job 的演练实例关联和结果；
- `job_instance/partition/task.dry_run=true` 继续作为执行链上的强制隔离标记；
- 正式 `batch_day_instance`、正式 SLA、catch-up 和跨日依赖不读取演练结果；
- `result_version` 只允许 `DRY_RUN`，永远不能自动或人工晋升为 `EFFECTIVE`。

第一阶段只支持“已有业务日的历史实例演练”；未来业务日的“按调度计划整日预演”在第二阶段接入 L2
Schedule Plan，避免一次同时改候选解析和执行隔离两条主链。

## 2. 为什么不修改 batch_day_instance

当前正式批量日身份为：

```text
tenant_id + calendar_code + biz_date
```

若把 `dry_run` 加入唯一键，同一天将出现正式记录和多条演练记录，而现有结算器、门禁、跨日依赖、Console 查询和
catch-up 都按三元组查单行。直接扩键会造成多行歧义，并扩大到全部批量日 SQL。

`batch_day_instance.dry_run` 已由 V115 添加，但生产代码没有创建 `dry_run=true` 的批量日实例。该列暂时保留用于滚动兼容，
新能力不再依赖它。稳定运行一个版本后，可通过单独迁移评估删除；在此之前查询必须显式按 `dry_run=false` 处理正式批量日。

## 3. 能力定位

### 3.1 目标

1. 在不修改业务表、不对外分发、不改变有效结果版本的前提下，批量演练某业务日全部或部分 Job。
2. 复用真实控制面路径，验证候选解析、实例创建、分片、任务派发、Worker 可达性、参数和权限契约。
3. 提供会话级审批、进度、失败明细、取消、容量限制和完整审计。
4. 支持同一业务日多次历史演练，每次结果独立可追溯。

### 3.2 非目标

- 不以“真实执行后事务回滚”模拟 dry-run；外部 IO 无法统一回滚。
- 不承诺验证最终业务数据值正确，因为写业务表和真实分发被禁止。
- 不允许单个实例中部分步骤 dry-run、部分步骤真实执行。
- 不让演练结果唤醒正式跨日 DAG、触发 catch-up、发送正式通知或改变 SLA。
- v1 不提供 dry-run 与正式结果的自动 diff。

## 4. 两种演练来源

### 4.1 Stage A：历史业务日演练

候选来自该业务日已有 `job_instance`，复用 replay 当前的 `ALL/ALL_FAILED/SUBSET_JOB_CODES` 解析逻辑：

- 选择每个 job 最新、符合 scope 的 source instance；
- 复用 source 的参数快照和原配置版本策略；
- 新建 `dry_run=true` 的 rerun instance；
- entry 保存 `source_instance_id` 和 `rerun_instance_id`。

这是首个可交付版本，改动最小，适用于版本升级、故障修复和历史日全链路演练。

### 4.2 Stage B：未来/空业务日计划演练

当目标业务日没有历史 instance 时，候选来自 ADR-026 L2 Schedule Plan：

- 根据 calendar、holiday、window、enabled、schedule 和依赖规则解析应运行 Job；
- entry 的 `source_instance_id` 为空，保存不可变 `plan_snapshot`；
- dispatcher 走 definition launch，而不是 rerun；
- 所有实例仍携带 `replay_session_id` 和 `dry_run=true`。

Stage B 需要验证计划解析与真实 Trigger 的候选口径一致，单独上线，不能用“查询全部 enabled Job”替代调度计划。

## 5. 数据模型

### 5.1 Session 扩展

建议新增迁移：

```sql
ALTER TABLE batch.batch_day_replay_session
    ADD COLUMN execution_mode VARCHAR(24) NOT NULL DEFAULT 'REPLAY';

ALTER TABLE batch.batch_day_replay_session
    ADD CONSTRAINT ck_replay_session_execution_mode
    CHECK (execution_mode IN ('REPLAY', 'DRY_RUN'));
```

archive 镜像必须在同一迁移同步增加列。

`execution_mode=DRY_RUN` 时增加应用层与数据库约束：

- `scope != OUTPUTS_ONLY`，因为 Dry-run 不允许 promote 历史结果；
- `result_policy=DRY_RUN_ONLY`；
- 创建出的全部 rerun instance 必须 `dry_run=true`；
- session 完成不能触发 result promotion 或正式完成事件。

建议把 `DRY_RUN_ONLY` 加入 result policy CHECK，但不加入普通 rerun 的可选项。它是 session 内部固定策略，不由前端自由传入。

现有 active session 唯一索引保持不变：同一 `(tenant, calendar, bizDate)` 任意时刻只允许一个 replay 或 dry-run session
处于 `PENDING_APPROVAL/RUNNING`。这会牺牲同日 replay 与演练并行，但能避免两类批量任务争抢容量，符合上线初期的保守策略。

### 5.2 Entry 扩展

Stage A 不需要新增必填列。Stage B 建议增加：

```sql
ALTER TABLE batch.batch_day_replay_entry
    ADD COLUMN plan_snapshot JSONB;
```

约束语义：

- 历史演练：`source_instance_id IS NOT NULL`；
- 计划演练：`source_instance_id IS NULL AND plan_snapshot IS NOT NULL`；
- `rerun_instance_id` 一旦写入不可替换；
- entry 终态后不可恢复到 `PENDING/RUNNING`。

## 6. 状态机与幂等

Session 沿用现有状态机：

```text
PENDING_APPROVAL -> RUNNING -> SUCCEEDED
                         \-> PARTIAL_FAILED
                         \-> CANCELLED
```

Entry 沿用：

```text
PENDING -> RUNNING -> SUCCEEDED
                   \-> FAILED
PENDING -----------> SKIPPED
```

新增不变量：

1. session 的 `execution_mode` 创建后不可修改。
2. Dry-run entry 只能关联 `dry_run=true` 的 instance。
3. Dry-run instance 只能终结为 `SUCCESS_DRY_RUN/FAILED_DRY_RUN`。
4. entry claim 必须使用数据库 CAS 或 `SKIP LOCKED`，多 orchestrator 实例不能重复 launch。
5. 终态回填按 `(tenant_id, replay_session_id, rerun_instance_id)` 幂等。
6. session 计数由 entry 真状态聚合或原子增量维护，最终终态前执行一次事实校验。

建议 dry-run launch 幂等键包含：

```text
tenantId + sessionId + entryId + jobCode + bizDate + DRY_RUN
```

不得复用正式 instance 的 dedup key，否则演练可能被正式任务去重，或反向阻断正式任务。

## 7. 副作用隔离

### 7.1 必须允许

- 平台库中的 session、entry、job instance、partition、task、审计、指标和 trace 写入；
- Orchestrator 到 Worker 的控制面 Outbox/Kafka 派发；
- Worker 到 Orchestrator 的 heartbeat、claim、renew 和 report；
- 只读访问业务源、元数据、对象存储对象属性和下游可达性探针；
- `result_version(status=DRY_RUN)` 元数据。

### 7.2 必须禁止

- 写业务表、staging 和业务 checkpoint；
- 创建或覆盖正式文件对象；
- SFTP/NAS/OSS/HTTP/邮件等真实外部分发；
- 写会被外部消费者读取的业务 Outbox；
- promote、asset partition 生效、正式 lineage 生效和跨日依赖唤醒；
- 更新正式 `batch_day_instance` 的状态与计数。

ADR-026 中“dry-run 不写 outbox_event”需要按边界澄清：**业务副作用 Outbox 禁止，控制面任务派发 Outbox 必须允许**，
否则 Worker 不会收到演练任务。

### 7.3 五类 Worker 验收

| Worker | Dry-run 允许 | Dry-run 禁止 |
|---|---|---|
| Import | 读取源文件、解析、校验、分片估算 | 写 staging/业务表、推进正式 checkpoint |
| Export | SQL EXPLAIN/只读抽样、模板与路径解析 | 生成正式对象、multipart 上传、发布 manifest |
| Process | 源 SQL 校验、执行计划、参数和 schema 验证 | staging、目标表 merge/copy、正式 watermark |
| Dispatch | 渠道配置、凭据引用、目标路径和连通性校验 | 真实 NAS/SFTP/OSS/HTTP 传输和回执推进 |
| Atomic | SQL read-only explain、HTTP HEAD、安全策略检查 | DML/DDL、存储过程副作用、Shell、真实 HTTP mutation |

所有禁止项必须在共享 `DryRunGuard`/capability 层 fail-close，不能依赖每个步骤零散 `if (dryRun)`。

## 8. API 与权限

优先扩展现有批量日 replay API，不增加平行控制器：

```json
POST /api/console/ops/batch-day-replay/sessions
{
  "calendarCode": "settlement",
  "bizDate": "2026-09-08",
  "scope": "ALL",
  "executionMode": "DRY_RUN",
  "candidateSource": "EXISTING_INSTANCES",
  "reason": "release-1.1 rehearsal",
  "autoApprove": false
}
```

服务端规则：

- 普通 replay 默认 `executionMode=REPLAY`，保持 wire 向后兼容；
- `DRY_RUN` 强制覆盖 result policy，不接受客户端传 promotion policy；
- Stage A 只接受 `EXISTING_INSTANCES`；Stage B 开放后增加 `SCHEDULE_PLAN`；
- 先调用 preview，再审批 submit；大规模会话必须审批；
- 返回 session/entry 时显式包含 `executionMode`、`dryRun` 和副作用跳过摘要。

权限建议：

- `batch_day_replay.dry_run.preview`
- `batch_day_replay.dry_run.submit`
- `batch_day_replay.dry_run.approve`
- `batch_day_replay.dry_run.cancel`

`approve` 不与普通 replay promote 权限共用，避免拥有演练权限的用户获得正式版本切换能力。

Console 前端在现有“批量日重放”页面增加 `重放/演练` 模式分段控件；演练结果单独 tab 展示，不混入正式批量日列表。

## 9. 容量与调度

Dry-run 不产生外部副作用，但仍会占用真实控制面、Kafka、Worker CPU、源存储读取和平台数据库写入，不能视为零成本。

建议：

- 复用 replay dispatcher 的 batch size 和 rate limit；
- 增加独立 `batch.replay.dry-run.max-active-entries`；
- admission 同时检查租户配额与平台全局演练配额；
- 默认优先级低于正式任务，不得挤占生产结算窗口；
- 正式 batch window 进入关键时段时禁止新演练，运行中的会话可 drain/cancel pending entries；
- 指标统一带 `execution_mode=DRY_RUN`，默认生产 SLA 面板排除。

## 10. 统计、归档与保留

- 正式 batch-day 查询和结算显式限定 `job_instance.dry_run=false`；
- replay/dry-run session 自己聚合 dry-run 状态，不复用正式 batch-day metrics；
- `SUCCESS_DRY_RUN/FAILED_DRY_RUN` 纳入 job instance 终态和归档候选；
- dry-run result version 默认保留 7 天，可独立配置；
- session/entry 按现有 archive 镜像归档，不能只删 job instance 留孤儿引用；
- Console 通知使用独立演练模板，默认不触发正式成功/失败通知。

## 11. 安全与审计

- prod profile 必须 fail-close 校验 DryRunGuard 已注册；缺失时拒绝启动演练能力，而不是降级真实执行。
- 请求、审批、取消、每个被跳过的外部动作都写审计，敏感配置只记录引用和掩码。
- SQL dry-run 只允许 AST 验证后的只读语句或 `EXPLAIN (ANALYZE FALSE)`；禁止依赖 JDBC `readOnly` 作为唯一防线。
- HTTP 只允许 SSRF 校验后的 HEAD/OPTIONS 探针，不跟随到私网重定向。
- Atomic Shell 在 dry-run 下直接标记 `SKIPPED_UNSAFE_CAPABILITY`，不启动进程。
- SDK 自托管 Worker 必须通过 dry-run conformance 才能声明该 capability；不支持的 Worker 不得 claim 演练任务。

## 12. 失败与恢复

- dispatcher 崩溃：entry claim 租约到期后重领，dedup key 防重复创建 instance；
- Worker 崩溃：走现有 lease reclaim，但只重投 dry-run task；
- session cancel：只停止 PENDING entry，已 RUNNING 实例自然完成并回填，保持现有 replay 语义；
- 部分失败：session 进入 `PARTIAL_FAILED`，不自动重试；重新演练创建新 session；
- Orchestrator 在 instance 创建后、entry 关联前崩溃：reconciler 按 `replay_session_id + dedup_key` 回填关联；
- 所有恢复路径都不得触发正式 result promotion 或业务副作用补偿。

## 13. 实施计划

### Phase 0：先修当前缺陷（上线前）

1. dry-run 终态纳入归档。
2. 正式 batch-day 统计排除 dry-run，并把 `PAUSED` 归入非终态。
3. 修正文档：ADR-026 的整批量日入口尚未实现，不再声明当前可用。

预计：1–2 人天。

### Phase 1：历史业务日演练

1. session 增加 `execution_mode`，archive 镜像同步。
2. submit/preview/controller/OpenAPI/Console 类型加入兼容字段。
3. replay dispatcher 构造 `dry_run=true` rerun request 和独立 dedup key。
4. terminal reconciler 识别两个 dry-run 终态并只推进 session/entry。
5. result policy 强制 `DRY_RUN_ONLY`，禁止 promote。
6. 增加权限、审计、指标和容量限制。

预计：4–6 人天。

### Phase 2：五类 Worker 隔离闭环

1. 盘点五类 Worker 的所有业务写与外部 IO capability。
2. 统一接入共享 DryRunGuard，删除散落分支。
3. Java 与五语言 SDK conformance 增加 dry-run capability 声明。
4. 对每类 Worker 做真实依赖负向验证，证明目标系统零副作用。

预计：4–7 人天。

### Phase 3：未来业务日计划演练

1. 接入 L2 Schedule Plan 候选解析。
2. entry 增加 `plan_snapshot` 并支持无 source instance launch。
3. 与 Trigger 在日历、misfire、窗口、时区和跨日依赖上的候选集合对账。
4. Console 增加计划来源和候选差异展示。

预计：3–5 人天。

总预计：12–20 人天；AI 协作并已有 replay 基础时约 5–8 个工作日。Phase 0 单独完成，不等待增强功能。

## 14. 测试矩阵

### 单元测试

- submit/preview 对 execution mode 的默认与强制策略；
- dry-run dedup key 与正式 key 隔离；
- 每类 Worker guard 的允许/禁止 capability；
- session/entry 状态机和取消；
- source instance 与 plan snapshot 两种候选。

### PostgreSQL 集成测试

- replay 与 dry-run active session 互斥；
- entry 并发 claim 只有一个成功；
- dry-run instance 只能关联 dry-run session；
- `SUCCESS_DRY_RUN/FAILED_DRY_RUN` 回填和归档；
- 正式 batch-day metrics 不受演练数据影响；
- archive session/entry/job/result version 引用完整。

### 五类 Worker 真实依赖测试

- Import：源文件被读取，业务表/staging/checkpoint 零新增；
- Export：查询计划成功，对象存储零正式对象；
- Process：源查询校验成功，staging/目标表/watermark 零变化；
- Dispatch：连通性检查成功，远端目录/对象/回执零变化；
- Atomic：SELECT/EXPLAIN 可用，DML、存储过程、Shell、HTTP mutation 全部拒绝。

### E2E 与对抗测试

- 100/1,000 entry 演练，HTTP 错误率 0、全部进入终态；
- dispatcher/Worker/Orchestrator 在三个崩溃窗口恢复，无重复 instance；
- dry-run 与同日正式任务并发，正式任务不被去重、不读演练版本；
- 取消、超时、lease reclaim、Kafka 重投；
- 跨租户 session/entry/instance 访问返回 0 行或 403；
- 对业务数据库、对象存储、外部 MockServer 做前后快照，证明副作用为 0。

## 15. 上线与回滚

1. migration 只增加有默认值的列，旧版本仍可读写，先部署 schema。
2. 新代码默认关闭 `batch.replay.dry-run.enabled=false`。
3. staging 开启，完成五类 Worker conformance 和 1,000 entry E2E。
4. 生产先只开放 preview，再向管理员开放 submit/approve。
5. 回滚时关闭功能开关并等待运行 session drain；数据库新增列保留，不做回滚删除。

建议开关：

```yaml
batch:
  replay:
    dry-run:
      enabled: false
      candidate-source: EXISTING_INSTANCES
      max-active-entries: 200
      retention-days: 7
```

生产启动校验：功能开启时，五类内置 Worker 和允许 claim 的 SDK Worker 必须声明 dry-run capability；缺少任一强制隔离器时 fail-close。

## 16. 完成定义

只有同时满足以下条件，才能宣布“整批量日 Dry-run 已实现”：

1. 正式 batch-day 唯一性与状态完全不变。
2. session/entry 可审批、限流、取消、恢复并完整归档。
3. 五类 Worker 真实依赖测试证明业务副作用为 0。
4. dry-run 终态、指标、通知和结果版本与正式链完全隔离。
5. 控制面 Outbox 正常工作，业务副作用 Outbox 被禁止。
6. 1,000 entry E2E 和三类崩溃恢复测试通过。
7. OpenAPI、Console 类型、运维手册、告警与功能开关登记同步完成。

## 17. 2026-09-11 实施结果

已完成：

- V202 session/entry 扩展、archive 镜像、CHECK 约束及 dry-run retention 部分索引；
- 历史实例与 `SCHEDULE_PLAN` 两种候选物化，计划快照、确定性 request/dedup key、claim CAS 和超时回收；
- dry-run 不创建正式 `batch_day_instance`，结果只写 `DRY_RUN`，终态仅接受
  `SUCCESS_DRY_RUN/FAILED_DRY_RUN`；
- 五类内置 Worker capability fail-close，Java/Go/Python/TypeScript SDK 显式 opt-in；Rust 暂只提供协议常量，不宣称尚不存在的注册生命周期；
- Process 插件副作用短路，五类 Worker 静态守护修正为真实目录扫描并覆盖 Atomic executor；
- Dispatch 在创建投递记录、推进文件状态和调用远端渠道之前统一短路，演练仅回填内存态计划结果；
- Console 模式/候选来源交互、OpenAPI 和生成类型完成；配置默认值、Compose、Helm 与 Feature Switch registry 对齐；
- `DRY_RUN` result_version 按独立 7 天窗口原子归档到冷表后清理热表。

本地验证证据见
[`batch-day-dry-run-verification-2026-09-11.md`](../verifications/batch-day-dry-run-verification-2026-09-11.md)。
第 16 节第 3、6 项中的“真实外部依赖零副作用”和“1,000-entry 三类崩溃恢复”属于环境验收，
不能由单元测试或静态扫描替代；开关保持默认关闭，完成 staging 验收后才允许生产开启。
