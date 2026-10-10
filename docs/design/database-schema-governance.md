# 数据库表结构治理

本文定义平台库表、索引、分区和归档的治理边界。它不是一次性审计报告；新增表、索引、分区或归档链路时，应按这里的规则补齐说明与验证。

## 当前结论

当前数据库需要做结构治理，而不是本地容量治理。本地实库数据量很小，但 Flyway 已演进到较高版本，`batch` schema 中表和索引数量较多，后续如果继续按功能堆表、补索引而不定期复盘，会产生职责不清、重复索引和归档策略漂移。

治理原则：

- 不凭本地 `idx_scan=0` 删除索引。本地流量不足以代表生产查询模式。
- 不压缩历史 Flyway migration，除非已定义发布基线、备份、恢复和跨版本升级策略。
- 不把物理分区数量当成业务实体表数量。分区是容量与生命周期手段，不是领域模型扩张。
- 新增表必须能归入一个业务域，并说明热/冷属性、保留策略、租户键和主要查询入口。

## 表域分类

| 域 | 典型表 | 治理重点 |
|---|---|---|
| 定义配置 | `job_definition`、`workflow_definition`、`pipeline_definition`、`file_template_config`、`resource_queue` | 软删除唯一性、配置发布审计、租户隔离 |
| 运行态 | `job_instance`、`job_task`、`job_partition`、`workflow_run`、`pipeline_instance`、`pipeline_step_run` | 分区、终态一致性、热查询索引、归档 |
| 调度与触发 | `trigger_request`、`trigger_runtime_state`、`trigger_outbox_event`、Quartz 表 | 幂等、misfire、重试、调度窗口 |
| 运维控制 | `batch_day_replay_session`、`batch_day_replay_entry`、`batch_day_replay_preview_token` | 租户约束、一次性预览消费/提交结果恢复、短期过期清理与状态一致性 |
| 文件与对象 | `file_record`、`file_dispatch_record`、`file_error_record`、`file_audit_log` | 对象存储引用、校验和、来源定位、保留策略 |
| 观测与审计 | `console_operation_audit`、`alert_event`、`event_delivery_log`、`dead_letter_task` | TraceId 检索、告警闭环、冷热分层 |
| 控制台与权限 | `console_user_account`、`tenant`、`api_key`、`console_push_subscription` | 密码/密钥生命周期、四角色边界、审计 |
| AI 控制面 | `console_ai_*` | 附件隔离、供应商审计、成本统计和留存 |
| 归档 | `archive.*` | 与热表 schema 漂移检测、保留期和恢复路径 |

新增表若无法归入上述域，先补 ADR 或设计说明，再落 Flyway。

## 新增表 checklist

- 表名表达领域实体或运行态事实，避免以页面、按钮或临时需求命名。
- 多租户表必须有 `tenant_id`，并明确是否进入唯一约束或查询前缀。
- 运行态表必须有时间列用于保留策略和清理窗口。
- 写路径必须说明幂等键、唯一约束或重复写处理。
- 需要归档的热表必须说明归档表、归档触发条件和恢复口径。
- 需要分区的表必须说明分区键、预建窗口、默认分区告警和清理策略。
- JSONB 字段必须说明索引需求；禁止因为“不确定查询方式”先加宽泛 GIN 索引。
- Console 查询表必须说明分页排序字段，避免依赖数据库隐式顺序。

## 索引治理

索引治理分两步：先保留证据，再做 DDL。`scripts/db/inspect-schema-governance.sh` 会输出以下只读证据：

- 代码迁移文件最高版本与当前库 Flyway 最高成功版本；
- schema 表/索引数量；
- 大表、大索引和 dead tuple 概况；
- 默认分区是否落数据；
- `idx_scan=0` 的非唯一非主键索引；
- 同表重复定义索引；
- B-tree 左前缀覆盖候选；
- archive 表与热表的对应关系。

删除索引必须同时满足：

- staging 或生产 `pg_stat_user_indexes` 覆盖足够观察窗口；
- 候选索引 `idx_scan=0` 或已被更宽索引稳定覆盖；
- 关键查询 `EXPLAIN (ANALYZE, BUFFERS)` 不退化；
- 有单独 Flyway migration 和回滚说明；
- 不影响唯一性、外键、`ON CONFLICT` 目标和排序稳定性。

本地报告只能用于发现候选，不得作为 DROP 的唯一依据。

## 分区治理

分区表需要维护三个边界：

- 预建窗口：至少覆盖当前月和后续运行窗口，避免写入默认分区。
- 默认分区：只能作为兜底，默认分区有业务行时应进入告警和补建流程。
- 清理/归档：热分区清理前必须确认归档策略、审计留存和恢复入口。

新增分区表时，必须同步：

- 建分区脚本或迁移逻辑；
- 默认分区检查；
- 归档或保留期说明；
- 监控指标或巡检脚本入口。

## 归档治理

归档表不是简单的历史副本。每张归档表都应说明：

- 源热表；
- 归档触发条件；
- 保留期限；
- 查询入口；
- schema 漂移检查；
- 恢复或导出路径。

如果热表需要长期审计或合规留存，应优先纳入 `archive` schema 或对象存储导出治理，而不是依赖热表永久保留。

## 操作入口

本地或 staging 可执行：

```bash
bash scripts/db/inspect-schema-governance.sh
```

生成报告文件：

```bash
BATCH_SCHEMA_GOVERNANCE_REPORT=reports/schema-governance.txt \
  bash scripts/db/inspect-schema-governance.sh
```

默认读取 `.env.local` 中的 PostgreSQL 配置，也可通过 `PGHOST`、`PGPORT`、`PGDATABASE`、`PGUSER`、`PGPASSWORD` 覆盖。

## 验收口径

一次数据库治理改动完成前，至少说明：

- 影响哪些表、索引、分区和归档链路；
- 是否需要迁移、回填或锁表；
- 是否影响租户隔离、幂等、排序、分页、审计或恢复；
- 本地、staging 或生产使用了哪类证据；
- 未覆盖的生产规模风险是什么。
