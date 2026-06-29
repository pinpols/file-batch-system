# Asset Partition Readiness

> 日期:2026-06-30  
> 范围:P0-3 第一刀,把现有 `result_version` 生效链规范成 asset partition 读口径。

## 目标

BFS 的下游 readiness 不能只看 `job_instance` 最新 attempt 是否成功。真正能被下游消费的结果必须已经进入 `result_version.status=EFFECTIVE`。

这一刀做的是最小闭环:

- `job:{jobCode}:{bizDate}` 视为一个 job asset partition。
- `result_version` 的当前 `EFFECTIVE` 行视为该 partition ready。
- `PENDING / DRY_RUN / SUPERSEDED / ARCHIVED / 缺失` 都不 ready。
- `ReadinessService` 通过 `AssetPartitionService` 判断 readiness,不再直接扫 `job_instance` 状态。

## 当前模型

当前不新增 `data_asset / asset_partition` 物理表,避免把 P0 变成大迁移。`AssetPartitionSnapshot` 是 read facade:

| 字段 | 来源 |
|---|---|
| `tenantId` | 请求租户 |
| `assetCode` | `jobCode` |
| `bizDate` | 请求批次日 |
| `partitionKey` | `bizDate.toString()` |
| `businessKey` | `result_version.business_key` |
| `freshnessStatus` | 固定 `EFFECTIVE` |
| `versionNo / jobInstanceId / payload*` | `result_version` 当前 EFFECTIVE 行 |

## 边界

- 不做企业数据目录、字段级血缘、记录级血缘。
- 不判断业务金额/笔数是否正确；这仍由 DQ gate、对账规则和业务 worker 负责。
- 不缓存 asset partition。后续如果读取 QPS 确认需要,再按明确失效策略加缓存。
- 物理 `data_asset / asset_partition` 表留到需要多 asset 类型、freshness SLA、Console 聚合页时再加。

## 验收口径

- 上游没有 EFFECTIVE 版本:readiness 返回 not ready。
- DQ BLOCKED 导致 result_version PENDING:readiness 返回 not ready。
- dry-run 产生 DRY_RUN 版本:readiness 返回 not ready。
- 同 businessKey 旧 EFFECTIVE 被新 EFFECTIVE supersede 后,下游只消费当前 EFFECTIVE。
