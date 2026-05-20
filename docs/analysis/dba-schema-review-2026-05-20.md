# DBA Schema 审查报告（file-batch-system）

- 日期：2026-05-20
- 范围：`docker/postgres/init/*.sql` + `db/migration/V*__*.sql`（V1–V133+）+ `scripts/db/*.sql`
- 视角：DBA + 软件工程师
- 关注点：Schema 设计与范式 / 索引与查询性能 / 分区与归档生命周期 / 约束与一致性

---

## 1. 总体结论

Schema 整体已较为成熟：

- **多租户隔离**：`tenant_id` 普遍下沉到业务表（V84/V85 已修复 workflow_node、workflow_edge），UNIQUE 约束大多包含 `tenant_id`。
- **约束体系**：CHECK 枚举（status、priority 范围）、UNIQUE、partial UNIQUE（V124 修复 NULL 绕过）覆盖较全。
- **归档机制**：`archive.*` 影子库 + `ArchiveSchemaDriftCheck` 启动校验已建立。
- **持续硬化迹象**：V84/V85（tenant 收口）、V124/V127（约束 NOT VALID + VALIDATE）、V130–V132（console_operation_audit + 归档 + 策略种子）。

**主要风险集中在三处**：

1. 大表未分区（outbox_event、job_instance 已有迁移脚本待执行）。
2. 部分热表缺少生命周期策略（trigger_outbox_event / dead_letter_task / job_execution_log）。
3. 索引在多次迁移中累积，存在冗余与覆盖范围错配。

无数据完整性级别的紧急缺陷。

---

## 2. 表清单（按域）

| 域 | 主要表 | 量级估算 | 备注 |
|---|---|---|---|
| 调度 | job_definition / job_instance / job_partition / job_task / trigger_request / trigger_outbox_event | 1000 万 + | 核心执行，partition 表可达亿级 |
| 工作流 | workflow_definition / workflow_node / workflow_edge / workflow_run / workflow_node_run | 100 万 + | DAG 编排，V84/V85 已合规 |
| 文件处理 | file_record / pipeline_definition / pipeline_instance / pipeline_step_run / file_dispatch_record | 500 万 + | 导入 / 导出 / 派发，V124 修复 checksum NULL 绕过 |
| 审计 / 事件 | outbox_event / event_delivery_log / dead_letter_task / job_execution_log / console_operation_audit | 5000 万 + | 中央事件总线，分区脚本就绪 |
| 配置 | resource_queue / batch_window / business_calendar / worker_registry / file_template_config | 10 万级 | 元数据，JSONB 配置较多 |
| 归档 | archive.* 系列 15+ 表 | 冷存 | 1:1 镜像，启动校验 drift |

---

## 3. Top 10 问题（按严重度）

### 3.1 outbox_event 表未分区，存在无界增长风险
- **证据**：V7 创建 `batch.outbox_event`（BIGSERIAL，无分区）；V133 加 GIN + 复合索引但未分区。
- **影响**：OutboxPollScheduler 扫描随表增长线性退化，VACUUM 锁写入；预计可达 7–10 亿行。
- **建议**：执行 `scripts/db/partition-migration/01-outbox-event-partitioned.sql`，按 `created_at` 月分区；Flyway + cron 每 4 周自动建分区；维护窗口内双写切换。

### 3.2 job_instance 缺 biz_date 维度索引
- **证据**：V5/V8/V133 索引未覆盖 `(tenant_id, biz_date DESC, instance_status)`；但 `cleanup-success-instances.sql` 等清理脚本按 `finished_at`/`biz_date` 过滤。
- **影响**：清理与控制台"按业务日期筛选"在亿级数据时退回全表扫描。
- **建议**：新增 `idx_job_instance_biz_date_status (tenant_id, biz_date DESC, instance_status)`；活跃实例再加 partial index `WHERE instance_status IN ('CREATED','WAITING','READY','RUNNING')`。

### 3.3 workflow_run 主键不含 tenant_id
- **证据**：V5 创建 `workflow_run` PK 仅为 `(id)`，V124 增加部分 UNIQUE 但 PK 仍未含租户。
- **影响**：跨租户隔离仅靠应用层 WHERE，DAO 若漏带 tenant_id 即穿透。
- **建议**：添加 `UNIQUE(tenant_id, id)` 兜底索引；审计 workflow_run 查询路径强制 tenant_id 过滤；可考虑在 `BaseTenantInterceptor` 加强制断言。

### 3.4 V124 NOT VALID 约束的窗口期风险
- **证据**：V124 引入 `NOT VALID` CHECK/FK，V127 VALIDATE。
- **影响**：若 VALIDATE 失败回滚，约束逻辑生效但旧数据未校验，新写入合规、旧数据隐性违例。
- **建议**：启动期 `NotValidConstraintGuard` fail-fast 检测 `pg_constraint` 中残留 NOT VALID；规范"NOT VALID 与 VALIDATE 同 PR/同 sprint"。

### 3.5 archive schema drift 检查未必覆盖 console_operation_audit
- **证据**：V130 创建表，V131 镜像归档表，V132 策略种子；`ArchiveSchemaDriftCheck` 测试需确认已纳入。
- **影响**：后续 ALTER 若遗漏 archive 镜像，归档静默不同步，冷数据恢复失败。
- **建议**：扩展 drift 检查至列级比对；建立"`batch.*` 表 DDL 必同 PR 增 `archive.*_archive` 迁移"硬约束（PR template / pre-commit）。

### 3.6 file_dispatch_record FK 缺级联策略
- **证据**：V6 `file_dispatch_record.file_id REFERENCES file_record(id)`，无显式 ON DELETE，默认 RESTRICT。
- **影响**：归档 / 清理顺序错误会阻塞 file_record 删除，事务回滚导致清理任务卡死。
- **建议**：改为 `ON DELETE CASCADE`（若是审计性质则 `SET NULL`）；清理脚本中显式记录删除顺序。

### 3.7 job_partition.idempotency_key 的 partial UNIQUE 仍存 NULL 漏洞
- **证据**：V124 把 UNIQUE 改为 partial unique `WHERE idempotency_key IS NOT NULL`。
- **影响**：GENERAL 任务允许多行 NULL，理论上 CLAIM 去重可被穿透，存在双重执行风险。
- **建议**：应用层强制 NOT NULL（UUID 占位）或 CLAIM 前显式去重；代码审计 worker/orchestrator CLAIM 路径。

### 3.8 dead_letter_task 无归档与清理
- **证据**：V7 创建 `batch.dead_letter_task`，scripts/db 无对应清理脚本，无 archive 镜像。
- **影响**：无界增长，孤儿行（source_id 指向已删除 partition）累积，取证查询恶化。
- **建议**：新增 `archive.dead_letter_task_archive`；制定保留：GIVE_UP > 90 天清理、NEW > 14 天告警；接入 DefaultArchiveService 周级归档。

### 3.9 file_record.metadata_json 的 JSONB 查询计划不稳
- **证据**：V133 加 `idx_file_record_metadata_json_gin`；FileArrivalGroupMapper 使用 `metadata_json ? 'key'` 查询。
- **影响**：JSONB 统计信息不足时 planner 低估选择率，并发高负载下退回 seq scan。
- **建议**：定期 `ANALYZE` + 评估 partial GIN `WHERE metadata_json IS NOT NULL`；EXPLAIN 抽样验证。

### 3.10 trigger_outbox_event relay 无租户游标
- **证据**：V80 创建表，V133 加 `(publish_status, next_publish_at)` 全局扫描索引；relay 无 per-tenant 进度。
- **影响**：relay 重启后状态丢失可能引发同事件重发风暴。
- **建议**：增加 `(tenant_id, publish_status, next_publish_at)` 复合索引；引入 `relay_state` 或 shedlock 存每租户游标；下游消费 Kafka 端配合幂等去重。

---

## 4. Schema 层观察

### 4.1 字段类型
- **VARCHAR 宽度**：`file_record.storage_path VARCHAR(1024)` 偏窄，S3 带 query 的 URL 易超；建议改 `TEXT`。
- **TIMESTAMPTZ**：默认微秒精度，足够；应用层应统一 UTC，禁止业务代码覆写 `created_at`。
- **NUMERIC**：priority INTEGER 1–9、partition 计数 INT 非负 CHECK 都到位。

### 4.2 NULL 与默认值
- 多个外键可空（`trigger_request_id`、`related_job_instance_id`、`pipeline_instance.file_id`）：技术上允许但缺乏逻辑互斥 CHECK，存在"已创建但无来源"的脏状态空间。建议针对性增加 `CHECK (created_at IS NOT NULL AND (trigger_request_id IS NOT NULL OR trigger_type='MANUAL'))` 一类约束。

### 4.3 JSONB 使用
- 合理位点：`param_schema` / `node_params` / `payload_json` / `metadata_json` / `config_json`。
- 当前仅 `file_record.metadata_json` 建了 GIN，按查询需要逐步评估其他列。
- 不建议预先把 JSONB 拆成关系表，除非查询命中其内部 key 已成热点。

### 4.4 多租户
- 核心业务表 OK；建议把 console 系列（console_operation_audit、console_user_account）UNIQUE 也明确含 `tenant_id`（若控制台允许多租户运营）。
- 例外（按设计豁免）：`batch_runtime_default_parameter`、`step_registry`、`shedlock`、`biz_table_schema`。

---

## 5. 索引层观察

### 5.1 冗余 / 覆盖错配
- `job_instance` 在 V8 / V133 至少三条复合索引覆盖近似谓词，建议先 profile（`pg_stat_user_indexes`）再合并为单一 `(tenant_id, instance_status, started_at DESC, biz_date)`。
- `workflow_run` 同理：V8 与 V133 索引可合并为 `(tenant_id, run_status, biz_date DESC, started_at)`。

### 5.2 缺失
- `event_delivery_log.outbox_event_id` 无独立 FK 索引，导致清理 outbox 时级联扫描慢。
- `job_partition` 缺裸 `(job_instance_id)` 前缀，部分范围扫描走不到组合索引。

### 5.3 Partial Index 机会
- `job_instance` 活跃实例：`WHERE instance_status IN ('CREATED','WAITING','READY','RUNNING')`。
- `file_record` 未删除：`WHERE file_status != 'DELETED'`。

### 5.4 复合索引顺序
- 现状已遵循"tenant_id → 过滤列 → 排序列 DESC"的良好惯例，保持即可。

---

## 6. 分区与生命周期观察

### 6.1 分区就绪但未执行
- `scripts/db/partition-migration/01-outbox-event-partitioned.sql`：outbox_event RANGE by `created_at`，月分区。
- `scripts/db/partition-migration/02-job-instance-partitioned.sql`：job_instance 按 biz_date 分区。
- **行动**：合并到同一维护窗口执行，避免中间状态膨胀；Flyway V134/V135 增加自动建分区作业。

### 6.2 清理保留矩阵
| 表 | 当前策略 | 状态 |
|---|---|---|
| job_instance | SUCCESS 30 天 / FAILED 1 小时 | OK |
| outbox_event | PUBLISHED 7 天 / GIVE_UP 30 天 | OK |
| event_delivery_log | 跟随 outbox_event 级联 | OK |
| **trigger_outbox_event** | 无 | **缺失** |
| **dead_letter_task** | 无 | **缺失** |
| **job_execution_log** | 无 | **缺失** |

### 6.3 归档同步
- `ArchiveSchemaDriftCheck` 在启动期校验，但需补足列级比对与 console_operation_audit 覆盖。

---

## 7. 约束与一致性观察

- **PK/FK**：核心表都是 `BIGSERIAL PK(id)`；近期迁移（V58、V119）将 job_partition 子表的 FK 改为 CASCADE/SET NULL，老表仍是默认 RESTRICT，需逐张审视。
- **CHECK**：状态枚举与数值范围覆盖完整；V124/V127 关键 NULL 绕过已修复。
- **租户隔离**：核心 OK；console 系列需收紧。
- **Outbox 事务边界**：`trigger_outbox_event` / `outbox_event` 与父事务同提交（V80、CLAUDE.md）；`event_delivery_log` / `worker_report_outbox` 与父事务关系需在文档中显式标注，避免后续重构破坏。

---

## 8. Quick Wins（≤ 1 天）

1. 增加 `event_delivery_log.outbox_event_id` FK 索引。
2. `job_instance` 活跃实例 partial index。
3. 新增 `cleanup-trigger-outbox-events.sql`（参考 `cleanup-outbox-events.sql`）。
4. `file_record.storage_path` 改为 `TEXT`。
5. `job_instance` 增加"created_at NOT NULL 且（trigger_request_id NOT NULL 或 trigger_type='MANUAL'）" CHECK。
6. `ArchiveSchemaDriftCheck` 纳入 `console_operation_audit`；扩展为列级比对。

## 9. 多日重构

1. **分区执行（2–3 天）**：01/02 partition migration 双切换 + Flyway V134/V135 周期建分区 + 归档 schema 同步验证。
2. **生命周期补齐（2–3 天）**：`DefaultArchiveService` 接入 trigger_outbox_event / dead_letter_task / job_execution_log，并为各表生成 archive 镜像。
3. **索引整合（1–2 天）**：基于 `pg_stat_user_indexes` 取证后合并 job_instance / workflow_run 冗余索引，新增 partial 索引。
4. **约束 CI 守护（1 天）**：Flyway 回调扫描 `pg_constraint` 残留 `NOT VALID`，存在即阻断部署；强制 NOT VALID 与 VALIDATE 同 PR。
5. **归档 drift 强化（1 天）**：`ArchiveSchemaDriftCheck` 列级、注释级比对全表覆盖。

---

## 10. 行动建议（建议排期）

| 优先级 | 事项 | 负责域 | 工期 |
|---|---|---|---|
| P0 | outbox_event 分区执行 | 调度 / DBA | 2–3 天 |
| P0 | trigger_outbox_event / dead_letter_task 生命周期 | 调度 / 归档 | 2 天 |
| P1 | job_instance 索引整合 + biz_date 索引 | 调度 | 1–2 天 |
| P1 | NOT VALID 守护 + drift 列级比对 | 平台 / CI | 1 天 |
| P2 | storage_path TEXT、event_delivery_log FK 索引、CHECK 补齐 | 平台 | 0.5 天 |
| P2 | workflow_run 兜底 UNIQUE + DAO 审计 | 工作流 | 1 天 |

---

附：本报告基于 V133+ 的静态分析，建议执行前再对照生产 `pg_stat_*` 视图取证，避免索引整合误删活跃索引。
