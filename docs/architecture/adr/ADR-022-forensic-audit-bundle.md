# ADR-022 · Forensic 一键取证（Forensic Audit Bundle）

- **Status**: Accepted（实施 gated — 见"实施触发条件"）
- **Date**: 2026-05-06
- **Supersedes**: —
- **Related**: ADR-011（idempotency）/ ADR-013（distributed tracing）/ §14.3.2

## 背景

监管 / 审计场景：

> "请说明 2026-03-15 0:30-3:00 期间，DAILY_PNL 为什么晚出 30 分钟。当时跑的是哪一版 job_definition？谁在那之前改过 cron？quota policy 是多少？哪个 worker 跑的？trace 全链路给我看下。"

当前散落在 ≥ 6 张表/文件：

| 数据 | 当前位置 | 缺什么 |
|---|---|---|
| job 状态时序 | `job_instance` + `job_execution_log` | OK |
| 配置历史 | `job_definition_version`（仅 job） | calendar / window / quota policy 没历史化 |
| 触发审批 | `approval_request` | OK 但散在多表 |
| 治理操作 | `batch_day_operation_audit`（V105） | OK |
| 限流 / quota 触发 | metric only | 没持久化决策日志 |
| Tracing | OTel + Loki | 30 天保留，监管要 7 年 |

**痛点**：

- 监管 7 年保留 vs Loki / OTel 30 天保留 → trace 早就丢了；
- calendar 改 holiday、quota policy 调阈值 → 改完只留当前值，历史不能回放；
- 出问题时 ops 要手工 join 6 张表凑时间线 → 几小时起步；
- 一旦改了一次 enum / 列名 → 历史 audit log 解释失败。

业界（金融 / 合规系统）都有"按 bizDate 一键 forensic export"。

## 决策

引入 **ForensicExportService** 一等公民 + 配置历史化：

- 配置类表全部加 `*_history` 影子表（写时 INSERT，更新时 INSERT + soft delete）；
- 一键 API：`POST /api/console/forensic/export {tenantId, bizDate, scope}` → 返回打包文件（JSON + CSV）+ S3 URL；
- 7 年保留：forensic snapshot 落对象存储（OSS / S3），元数据索引在 PG。

### 配置历史化清单

需要历史化的配置表（当前只有 `job_definition_version` 已落 ADR-017 前置）：

```sql
batch.business_calendar_history       -- 含 holiday / cutoff_time / late_arrival 阈值历史
batch.batch_window_history
batch.quota_policy_history
batch.tenant_quota_policy_history
batch.alert_routing_history
batch.workflow_definition_history     -- DAG 结构历史
batch.workflow_node_history           -- 节点配置 / 跨日依赖历史
batch.calendar_holiday_history        -- 调休变更历史（重要 — 春节年年改）
```

每张 *_history 表：

```
id BIGSERIAL,
target_id (原表 PK),
tenant_id,
operation VARCHAR(16),      -- INSERT / UPDATE / DELETE
before_json JSONB,
after_json JSONB,
changed_at TIMESTAMPTZ,
changed_by VARCHAR(128),
trace_id VARCHAR(128)
```

写入路径：MyBatis interceptor / Spring AOP 在 update / delete 时自动追加 *_history 行。

### Forensic Export Bundle

`POST /api/console/forensic/export` body:

```json
{
  "tenantId": "t1",
  "bizDateRange": {"from": "2026-03-15", "to": "2026-03-15"},
  "scope": ["job_instances", "config_snapshot", "approvals", "operations", "alerts", "trace"],
  "jobCodes": ["DAILY_PNL"],   // 可选过滤
  "exportFormat": "BUNDLE"     // BUNDLE / JSON / CSV
}
```

返回：

```json
{
  "exportId": "fex_20260315_t1_xxx",
  "status": "PROCESSING",
  "estimatedSize": "12 MB",
  "downloadUrl": "oss://...?expires=..."
}
```

异步生成（大 bizDate 范围可能几分钟），写 `forensic_export_log` 表跟踪进度。

### 7 年保留策略

```
batch.forensic_export_log         -- 每次 export 的元数据（保留 PG 中）
  id, tenant_id, scope, requested_at, requested_by,
  oss_path, file_size, sha256, retention_until

archive_storage = OSS / S3 bucket "batch-forensic-{tenant}"
  - 按 tenant + bizDate 前缀分 partition
  - 启用对象锁（compliance mode）防被改 / 删
  - 生命周期策略：> 7 年 → 删
```

## 影响面

| 维度 | 影响 |
|---|---|
| 持久层 | 7+ 张 *_history 影子表 + 1 张 forensic_export_log；archive 镜像同步 |
| 模块 | MyBatis interceptor 全局接入；orchestrator 加 ForensicExportService；OSS client 集成 |
| 性能 | 写路径每次 update 多 1 次 INSERT *_history（~ms 级）；可批量 buffer 异步刷 |
| 存储 | *_history 表年增长按业务量；forensic OSS 按导出实际生成 |
| 兼容 | 启用前的历史改动无法回填（只能记 changed_at >= 启用时刻）|

## 实施分阶段

| Stage | 范围 | 估算 |
|---|---|---|
| 1 | history 表 schema + MyBatis interceptor | 3 天 |
| 2 | 已有 *_history 接入 7 张配置表 | 3 天 |
| 3 | ForensicExportService + JSON / CSV 序列化 | 3 天 |
| 4 | OSS 集成 + 对象锁配置 | 2 天 |
| 5 | Console export API + 异步进度 | 2 天 |
| 6 | trace 长保留：OTel exporter 写 OSS（按 bizDate 分目录） | 3 天 |
| 7 | 守护测试 + 监管复盘演练 | 3 天 |

总 ~19-21 人天。

## 替代方案

| 方案 | 拒绝 |
|---|---|
| Debezium CDC 抽 PG WAL 写 Kafka 落对象存储 | 重资产，运维多一套 Debezium；金融场景 7 年保留追求确定性，Debezium 失败窗会丢数据 |
| 仅扩展现有 audit_log 表 | audit_log 是事件流，不能查"某时刻 quota policy 是多少"；需要快照 |
| 让运维手工导 SQL → 表格 | 6 张表 join 易错；无 attestation；不可重复 |

## 不变量

1. *_history 是 append-only，永不 UPDATE / DELETE（OSS 对象锁双重保障）；
2. forensic export 必须包含 `sha256` 摘要 + 时间戳，可独立验证完整性；
3. 7 年期内 OSS 对象不可改（compliance lock），即使 admin 也不行；
4. ForensicExportService 永远只读（不修业务状态）；
5. 配置回放：给定一个 (table, target_id, point_in_time) 必须能确定性重建当时的行内容。

## 验收

- 单测：MyBatis interceptor 写 *_history 全 CRUD 路径
- IT：典型监管场景"3 月 15 日 1:30 时 calendar 是怎样" point-in-time 重建
- E2E：完整 forensic bundle 生成 + sha256 校验 + OSS 对象锁验证
- 合规演练：每季度跑一次 forensic export 演习

## 实施触发条件

满足任一：
1. **受监管**：行业合规要求审计追溯保留 ≥ 5 年（银保监 / SOX / GDPR / HIPAA）；
2. **审计事故**：≥ 1 次发生"监管来查但数据找不全"的事件复盘；
3. **客户合同**：SaaS 合同显式要求"按需提供历史 forensic 报告"。

非受监管 + 无合同要求时不开工 — *_history 写路径成本不可忽略。

## 开放问题（已收口）

| # | 问题 | 决策 |
|---|---|---|
| 1 | *_history 跟原表 1:1 写还是异步 | **同步**。监管场景容不得"history 漏了"；写性能损耗 < 1ms 可接受 |
| 2 | 哪些表需要历史化 | 配置类（calendar / window / quota / approval policy / workflow definition）必做；运行态（job_instance / partition / task）不做（有 audit_log + status 流转记录足够） |
| 3 | trace 长保留 30 天 → 7 年怎么实现 | OTel exporter 双写：Tempo（30 天热查询）+ OSS parquet（7 年 cold）。forensic export 需要时按 bizDate 从 OSS 拉 |
| 4 | 跨租户 forensic | 仅 super_admin 角色 + 单独权限点 `forensic.cross_tenant_export`，默认禁用 |

### 不会做

- ❌ v1 不做交互式历史浏览 UI（浏览成本高、Console 现有表够 pull）；只提供 export bundle
- ❌ 不允许 forensic export 改任何业务状态（只读）
- ❌ 不接入 Splunk / SIEM 实时流（forensic 是"按需 pull"模型，不是"持续 push"）
- ❌ 不为非配置表加 *_history（job_instance / task 已有 status 流转 audit）
