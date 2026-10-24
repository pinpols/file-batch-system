# 数据库表结构审查与优化落地方案

## 1. 结论

当前表结构总体合理，已经形成了清晰的边界：

- `batch`：批量运行控制面、配置和运维状态。
- `batch_business`：业务目标表和 PROCESS 暂存区。
- `archive`：运行历史冷表镜像。
- 配置表、运行态表、事件表没有出现明显的职责混用。

本次审查没有发现需要立即重构主链路的致命表设计问题。需要优先治理的是数据生命周期和增长控制，而不是继续拆表。

## 2. 实测基线

检查环境：本地 Docker PostgreSQL 17，数据库 `batch_platform` / `batch_business`。本地数据不是生产容量证明，但可以暴露结构和清理策略问题。

| 表或对象 | 实测结果 | 判断 |
|---|---:|---|
| `batch.result_version` | 约 105.9 万行，约 804 MB | 当前最大未分区历史表；索引约 587 MB |
| `result_version` `SUPERSEDED` | 约 105.8 万行 | 历史版本占比极高，必须有生命周期闭环 |
| `result_version` `EFFECTIVE` | 约 400 行 | 当前有效版本数量正常 |
| `batch.outbox_event` 当前分区 | 约 168 MB | 已按 `created_at` 月分区 |
| `batch.job_instance` 当前分区 | 约 78 MB | 已按 `biz_date` 月分区 |
| `batch.process_staging` | 1 行，default 分区 0 行 | 已按 `staged_at` 分区，当前无残留风险 |

## 3. 表分类结论

### 3.1 配置表

`job_definition`、workflow/pipeline definition、`file_template_config`、通道、日历、配额等属于低频写入配置，不需要为追求形式上的范式而拆分。

`file_template_config` 约 50 列，字段覆盖 Excel、定长、分隔符、编码、压缩、加密、查询和校验配置。它偏宽，但属于配置宽表，不在运行态热路径上。暂不拆成多个格式表，避免增加版本、导入和兼容逻辑。

约束：今后不得继续向配置表加入任务心跳、重试计数、运行结果、实时进度等运行态字段；低频格式专属参数优先放已有 JSONB 配置中。

### 3.2 运行态表

`job_instance` 当前约 45 列，包含状态、调度、SLA、分片计数、重跑、结果摘要和水位信息，属于偏宽的核心控制面表。

当前不拆分。它是主链路高频查询和状态更新对象，强行拆分会增加跨表事务、并发更新和 RLS 复杂度。

只有在压测确认行宽或更新争用成为瓶颈后，才按以下方向垂直拆分：

1. `job_instance`：身份、状态、租约和调度核心字段。
2. `job_instance_sla`：deadline、SLA、窗口和数据区间。
3. `job_instance_rerun_context`：重跑、replay、父实例和操作人信息。
4. `job_instance_result`：结果摘要和高水位。

### 3.3 暂存表

`batch.process_staging` 是 PROCESS WAP 专用暂存表，不是配置表，也不与任务运行主表混用。当前已按 `staged_at` 分区，并有 `batch_key`、tenant 和孤儿清理索引，保持现状。

DIRECT 路径应继续作为大批量默认路径，JSONB staging 仅用于需要逐行校验、审计或恢复的场景。

### 3.4 事件和日志表

以下表属于持续增长的 append-only 或准 append-only 数据：

- `job_execution_log`
- `event_delivery_log`
- `webhook_delivery_log`
- `notification_delivery_log`
- `file_audit_log`
- `console_operation_audit`

这些表不应参与核心实例状态判断，应该统一采用“热数据保留 + archive + 清理”的生命周期。优先治理 `job_execution_log`、`event_delivery_log` 和 `webhook_delivery_log`；是否分区以生产行数、删除耗时和 dead tuple 为依据。

## 4. 本次优化范围与落地状态

### P1：result_version 生命周期闭环

状态：**已落地并完成本地真库验证**。`ResultVersionRetentionScheduler` 将过期 `SUPERSEDED` 行先写入 `archive.result_version_archive`，再标记热表为 `ARCHIVED`；超过 `archived-days` 的热表历史行按批次清理。archive 镜像不随热表清理删除。

落地要求：

1. `SUPERSEDED` 超过 `superseded-days` 后进入 `ARCHIVED`。
2. 进入 `ARCHIVED` 前复制到 `archive.result_version_archive`，使用幂等插入。
3. 超过 `archived-days` 的归档行按批次物理删除 **batch 热表**中的历史行；`archive.result_version_archive` 作为审计和 lineage 冷表，遵循独立的 archive 保留策略，不随热表清理删除。
4. 绝不删除 `EFFECTIVE`、`PENDING` 或仍被 replay / lineage 引用的热表版本；archive 镜像中的审计行不由该任务删除。
5. 每轮记录 archived、deleted、skipped 和失败数量。
6. 保留 `payload_ref`、版本号、业务键和状态等审计元数据；已清空的 `payload_json` 不恢复。

### P1：统一运行态表保留策略（分阶段，尚未全量启用）

复用现有 `archive_policy`，不新建第二套 retention 配置。**本轮不直接为没有冷表镜像和归档执行器的表打开策略**：`notification_delivery_log`、`file_audit_log`、`console_operation_audit` 等必须先具备对应 archive 镜像和复制/清理事务，否则只登记开关会制造“配置已启用但数据未归档”的假闭环。已有 `job_execution_log`、`event_delivery_log` 的基础设施保持现状，后续按真实容量和执行器证据逐表启用。

建议默认值：

| 表 | 热表保留 | archive | 说明 |
|---|---:|---:|---|
| `job_execution_log` | 30 天 | 开启 | 运行诊断正文和引用 |
| `event_delivery_log` | 30 天 | 开启 | 投递取证 |
| `webhook_delivery_log` | 30 天 | 开启 | 外部调用重试证据 |
| `notification_delivery_log` | 30 天 | 开启 | 通知投递记录 |
| `file_audit_log` | 90 天 | 开启 | 文件生命周期审计 |
| `console_operation_audit` | 365 天 | 开启 | 管理员操作审计，不默认物理删除 |

实际租户可以延长保留期，不能缩短到违反审计或合规要求的范围。

### P2：default 分区和容量观测（已补 runbook 查询，指标化暂缓）

已补充只读 SQL 查询脚本 [schema-capacity.sql](../../scripts/db/inspect/schema-capacity.sql)，不参与业务请求，也不会动态建分区或删除数据。增加只读观测：

- 每个分区父表的 default 分区行数。
- 月分区覆盖窗口是否不足。
- `result_version` 各状态数量和最老时间。
- 表数据大小、索引大小、dead tuple 和 vacuum 延迟。
- JSONB 载荷平均/最大字节数。

观测异常只产生指标和告警，不在业务请求中动态建分区或删除数据。

### P2：JSONB 和索引治理

1. 保留业务确实使用的 JSONB 索引。
2. 对 `outbox_event.payload_json` 的 GIN 索引做查询证据审查；没有 JSON 条件查询时不继续扩大索引。
3. 大型 payload 只保留必要摘要或外部引用，不能把业务原始数据复制进控制面表。
4. `job_instance` 的索引暂不删除，先基于 `pg_stat_user_indexes` 和真实查询计划做减法。

## 5. 明确不做

- 不全面拆分 `job_instance`。
- 不拆分 `file_template_config` 的格式字段。
- 不给所有运行子表强行分区。
- 不引入第二套归档配置表。
- 不把业务原始数据放入 `result_version`、outbox 或实例表。
- 不在业务请求路径执行归档、建分区或大批量删除。

## 6. 验收标准

### 正确性

- `EFFECTIVE`、`PENDING` 版本不会被清理。
- archive 插入和删除幂等，任务重复执行不会产生重复归档。
- 租户条件贯穿归档和删除 SQL。
- replay、lineage、console 历史查询仍能命中 archive。
- archive schema drift 检查继续通过。

### 性能

- retention 每轮按 batch size 处理，不锁住整张表。
- JSONB 大字段不出现在实例列表查询中。
- default 分区行数为 0。
- 归档/清理任务失败不会阻塞 launch、claim、report 主链路。

### 运维

- 指标能区分 archived、deleted、skipped、failed。
- 表大小、索引大小、dead tuple 和 default 分区均可查询。
- 有明确的 dry-run 和回滚方式。

## 7. 实施顺序与验证记录

1. **已完成**：补 `result_version` archive/cleanup 的 mapper、调度清理和单测；新增 V200 partial index。
2. **已完成**：archive 写入与热表状态更新在同一 SQL 事务内幂等执行；被 `asset_partition` 引用的版本不会删除。
3. **已完成**：增加分区/容量只读 runbook 查询；指标化留到有生产采样频率和告警阈值后再做。
4. **已完成**：本地 Docker PostgreSQL 17 真库验证 Flyway、archive schema drift 和 retention IT。
5. **上线前置**：生产启用物理删除前，先以 dry-run 运行一个完整保留周期，并观察 archive 命中、删除量、失败量、表大小和 dead tuple。

### 本轮验证记录（2026-09-07）

- `ResultVersionRetentionSchedulerTest`：6/6 通过。
- `LocalFlywayPlatformMigrationsIntegrationTest` + `ArchiveSchemaDriftCheckIntegrationTest`：7/7 通过，V200 已应用。
- `ArchiveColdStorageIntegrationTest`：4/4 通过；首次运行的既有归档断言受本地历史 fixture 影响出现一次重试，已改为不依赖全局数量的断言。
- 未运行全仓 full gate；本轮只验证受影响模块和数据库迁移/归档链路。

本文件是当前表结构基线和后续 schema 变更边界。后续新增表必须注明：所属层级、写入频率、保留策略、是否需要 archive、分区键和 default 分区处理方式。
