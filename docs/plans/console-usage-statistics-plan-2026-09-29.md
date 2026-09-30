# Console 使用率统计方案

状态：基础用量摘要已存在；日聚合统计方案尚未落地

> 复核日期：2026-09-30。当前系统已有租户用量摘要和 Dashboard 查询，但没有本文规划的 `console_usage_daily`、事件标准化、有界批量 upsert、独立 `usage-summary` 契约或使用率报表。以下方案仍是后续实施计划，不应把现有基础摘要误认为完整使用率统计。

## 1. 目标与边界

本方案为控制台增加低成本、可解释的使用率统计，回答以下问题：

- 哪些租户实际使用了配置、触发、重跑、补偿、导入、导出等能力；
- 某项能力在日、周、月窗口内的成功次数、失败次数和趋势；
- 哪些页面被访问，哪些功能入口长期没有使用。

本方案不把使用率统计扩展为通用行为分析平台，也不改变批量任务的主链路。

### 1.1 三类数据的职责

| 数据 | 权威性 | 用途 | 是否作为合规审计依据 |
|---|---|---|---|
| `console_operation_audit` | 后端成功/失败操作 | 谁在什么时候对哪个租户执行了什么写操作 | 是 |
| 业务状态和结果表 | 后端真实业务结果 | 导入、导出、重跑、补偿等业务成功率 | 是，按业务语义 |
| 前端 telemetry | 页面访问、点击、API/JS 错误 | 页面使用趋势和排障 | 否 |

前端点击不能证明业务操作成功。用户点击“导入”后请求可能被拒绝、超时或最终失败，业务使用率必须优先统计后端结果。

## 2. 当前状态

当前已经存在：

- 基础租户用量摘要接口：`GET /api/console/tenants/usage`；
- Dashboard 租户用量查询：`GET /api/console/dashboard/tenant-usage`；
- 对应的固定 DTO 和租户权限测试。

- 前端 `logger.ts`：最多 500 条本地环形日志，每 15 秒按最多 50 条批量上报；默认关闭；
- `POST /api/console/telemetry/events`：后端校验后通过 SLF4J/MDC 输出结构化日志；
- `batch.console_operation_audit`：由后端 `@AuditAction` 切面写入，支持租户、操作者、动作、结果和 traceId 查询；
- `GET /api/console/queries/operation-audits`：控制台操作审计查询入口。

当前没有：

- 独立的前端 telemetry 数据库表；
- `frontend-telemetry` Kafka topic；
- 日聚合表和使用率查询接口；
- 前端使用率报表页面。

## 3. 推荐架构

```text
前端 route telemetry ───────────────┐
                                    │
后端 operation_audit / 业务结果 ────┼─> Usage metric normalizer
                                    │       │
                                    │       ├─> bounded batch buffer
                                    │       └─> PostgreSQL daily upsert
                                    │
                                    └─> usage-summary query API
                                             │
                                      Console usage view / Grafana
```

### 3.1 数据来源优先级

| 指标 | 首选来源 | 备用来源 | 说明 |
|---|---|---|---|
| 页面访问 | 前端 `route` telemetry | 无 | 允许 best-effort，不能用于计费 |
| 功能入口点击 | 前端 `click` telemetry | 无 | 只做趋势参考，不代表成功 |
| 配置导入/导出 | 后端 operation audit + 业务结果 | 前端 click | 按成功、失败分别统计 |
| Job 触发、重跑、取消 | 后端 operation audit | 前端 click | 后端动作是唯一可信来源 |
| Worker 执行成功率 | `job_task` / `job_instance` | 无 | 只统计终态业务结果 |
| API 错误率 | 结构化 API 日志 | 前端 `error` telemetry | 前端数据用于补充页面维度 |

## 4. 日聚合表设计

建议表名：`batch.console_usage_daily`。

### 4.1 逻辑字段

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `bigserial` | PK | 内部标识 |
| `stat_date` | `date` | NOT NULL | 统计业务日期，按部署时区生成 |
| `tenant_id` | `varchar(64)` | NOT NULL | 后端认证上下文解析，不信任前端传值 |
| `source` | `varchar(32)` | NOT NULL | `OPERATION_AUDIT` / `BUSINESS_RESULT` / `FRONTEND` |
| `metric_code` | `varchar(96)` | NOT NULL | 后端白名单指标编码 |
| `page_code` | `varchar(128)` | NOT NULL DEFAULT `''` | 页面维度；非页面指标为空串 |
| `app_version` | `varchar(64)` | NOT NULL DEFAULT `''` | 前端版本，便于版本回归分析 |
| `event_count` | `bigint` | NOT NULL DEFAULT 0 | 发生次数 |
| `success_count` | `bigint` | NOT NULL DEFAULT 0 | 成功次数 |
| `failure_count` | `bigint` | NOT NULL DEFAULT 0 | 失败次数 |
| `last_seen_at` | `timestamptz` | NOT NULL | 最近一次聚合时间 |
| `created_at` | `timestamptz` | NOT NULL | 创建时间 |
| `updated_at` | `timestamptz` | NOT NULL | 更新时间 |

唯一键建议为：

```text
(stat_date, tenant_id, source, metric_code, page_code, app_version)
```

### 4.2 分区、索引和保留

- 按 `stat_date` **月度分区**，不按天创建大量分区；
- 主查询索引：`(tenant_id, stat_date DESC)`；
- 趋势查询索引：`(metric_code, stat_date DESC)`；
- 默认保留 400 天，过期分区按现有归档策略清理；
- 表启用严格 RLS，租户查询必须带当前租户上下文；
- `ROLE_ADMIN` 的跨租查询只能通过后端受控接口，不直接放开数据库全表读。

第一版不放 `unique_user_count`。精确 UV 需要额外的用户日去重表或近似算法，避免在聚合表中伪造精确值。

## 5. 写入策略

### 5.1 请求链路

1. 接收前端 telemetry 或后端业务事件；
2. 根据后端白名单将事件标准化为 `metric_code`；
3. 进入有界批量缓冲，不阻塞原始业务请求；
4. 定时按 1 分钟或达到批量阈值执行 PostgreSQL `INSERT ... ON CONFLICT DO UPDATE`；
5. 写入成功/失败、缓冲长度和丢弃数量指标。

使用率统计是非关键业务数据，允许缓冲溢出或进程重启导致少量丢失，但必须在文档和看板上标注为 best-effort。不得把该表用于计费、对账或审批取证。

### 5.2 并发和性能约束

- 不允许每个点击同步执行一次数据库写入；
- 批次内先按唯一键合并，再执行批量 upsert；
- 指标编码、页面编码和版本长度必须限制，禁止把任意用户输入作为维度；
- 对单租户、单指标设置突发上限，防止异常前端版本制造热点；
- 聚合写失败只影响统计，不回滚原始业务操作；
- 队列积压、丢弃、upsert 错误必须暴露 Prometheus 指标和告警。

## 6. 查询接口契约

建议新增：

```text
GET /api/console/queries/usage-summary
```

查询参数：

- `tenantId`：普通租户只能查询当前租户；
- `from` / `to`：最大查询窗口建议 400 天；
- `metricCode`：可选白名单指标；
- `pageCode`：可选页面；
- `groupBy`：仅允许 `date`、`metric`、`page`、`tenant` 的预定义组合。

返回必须使用稳定 DTO，不返回原始 Map。响应至少包含：

```text
statDate, tenantId, metricCode, pageCode,
eventCount, successCount, failureCount
```

接口权限建议：`ROLE_ADMIN`、`ROLE_AUDITOR`、`ROLE_TENANT_ADMIN`。普通租户用户暂不开放跨页面的全量统计，避免引入新的权限语义。

## 7. 指标命名规则

指标编码由后端维护，前端不得自由创建：

| 编码示例 | 来源 | 成功定义 |
|---|---|---|
| `ui.page.ops-summary.view` | 前端 route | 页面完成路由进入 |
| `operation.config-import` | operation audit | 配置应用接口成功提交 |
| `operation.job-trigger` | operation audit | 触发请求成功接受 |
| `operation.job-rerun` | operation audit | 重跑请求成功创建 session |
| `business.import.completed` | job/task 结果 | 对应实例进入 SUCCESS |
| `business.export.completed` | job/task 结果 | 导出文件和校验文件均成功 |
| `api.console.error` | 后端 API 日志 | 响应状态为 4xx/5xx |

同一功能的中文、英文文案、按钮文本变化不能改变 `metric_code`。

## 8. 分阶段实施与验收

### P0：指标和契约冻结

- 固化指标目录、权限、保留期和租户口径；
- 复核 operation audit 覆盖的写操作；
- 前端只保留页面访问和诊断点击，不将点击当作业务成功；
- 更新 OpenAPI、前后端 DTO 和文档索引。

验收：同一操作在中英文切换、重试和失败时不会产生错误的成功统计。

### P1：PostgreSQL 日聚合

- 新增 Flyway 表、RLS、月度分区和归档策略；
- 增加标准化器、有界缓冲和批量 upsert；
- 增加 `usage-summary` 查询接口；
- 增加租户、权限、并发 upsert 和保留策略测试。

验收：

- 租户 A 查询不到租户 B；
- 重复事件不会无限放大统计；
- 业务操作失败不会计入成功数；
- 聚合写失败不影响原始业务请求；
- 1000 个并发小批次下无连接池耗尽和锁等待扩散。

### P2：前端展示和运行治理

- 增加租户使用率趋势页面；
- 增加指标、页面、日期范围筛选；
- 增加聚合队列、丢弃数和写失败告警；
- 按版本观察埋点 schema 漂移。

验收：页面展示与直接 SQL 汇总结果一致，且可以定位版本和租户。

## 9. 明确不做

- 不把前端 telemetry 改造成操作审计；
- 不保存完整原始点击事件和请求体；
- 不在第一阶段引入 Kafka、ClickHouse 或通用行为分析平台；
- 不做跨租户用户画像、广告漏斗和复杂成本分析；
- 不用使用率统计裁定业务正确性，业务正确性仍由任务状态、文件校验、对账和审计记录决定。
