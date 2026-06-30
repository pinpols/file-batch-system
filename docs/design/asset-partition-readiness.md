# Asset Partition Readiness

> 日期:2026-06-30  
> 范围:P0-3 第二刀,把现有 `result_version` 生效链物化成最小 asset partition 读模型。

## 目标

BFS 的下游 readiness 不能只看 `job_instance` 最新 attempt 是否成功。真正能被下游消费的结果必须已经进入 `result_version.status=EFFECTIVE`。

这一阶段做的是最小闭环:

- `job:{jobCode}:{bizDate}` 视为一个 job asset partition。
- `result_version` 的当前 `EFFECTIVE` 行视为该 partition ready。
- `ResultVersionWriter` 自动生效、`ResultVersionPromoteService` 人工 promote 生效时刷新 `data_asset / asset_partition`。
- `PENDING / DRY_RUN / SUPERSEDED / ARCHIVED / 缺失` 都不 ready。
- `ReadinessService` 通过 `AssetPartitionService` 判断 readiness,不再直接扫 `job_instance` 状态。

## 当前模型

当前新增 `data_asset / asset_partition` 两张最小物化表,但不改变权威来源:

- `result_version` 仍是版本链和 EFFECTIVE 唯一性的权威。
- `asset_partition` 是 readiness / Console 查询读模型。
- 查询优先读 `asset_partition`;本地旧数据或迁移前数据未物化时,回退到 `result_version` 当前 EFFECTIVE 投影。

| 字段 | 来源 |
|---|---|
| `tenantId` | `asset_partition.tenant_id` |
| `assetCode` | `data_asset.asset_code`;JOB 类型下等于 `jobCode` |
| `bizDate` | `asset_partition.biz_date` |
| `partitionKey` | `asset_partition.partition_key`;JOB 类型第一阶段固定为 `bizDate.toString()` |
| `businessKey` | `asset_partition.business_key` |
| `freshnessStatus` | `asset_partition.freshness_status`;当前只物化 `EFFECTIVE` |
| `versionNo / payloadJson` | 通过 `asset_partition.result_version_id` 关联 `result_version` |
| `jobInstanceId / payloadStorage / payloadRef` | `asset_partition` 快照字段 |

## 写入时机

`ResultVersionWriter.writeOnTerminal` 在以下条件满足时物化:

- `job_instance` 已进入成功类终态。
- 非 dry-run。
- DQ gate 未 BLOCKED。
- promotion policy 允许 AUTO_LATEST,最终写入 `result_version.status=EFFECTIVE`。

`ResultVersionPromoteService.promote` 在人工把 `PENDING` 推进到 `EFFECTIVE` 后,也会按原 `job_instance` 刷新同一条 asset partition。

以下情况不写入 `asset_partition`:

- `PENDING`:需要人工确认或 DQ BLOCKED。
- `DRY_RUN`:只供核对,不能被下游消费。
- `FAILED / CANCELLED / TERMINATED`:不是可消费产物。

## 边界

- 不做企业数据目录、字段级血缘、记录级血缘。
- 不判断业务金额/笔数是否正确；这仍由 DQ gate、对账规则和业务 worker 负责。
- 不缓存 asset partition。后续如果读取 QPS 确认需要,再按明确失效策略加缓存。
- 当前只支持 `asset_type=JOB`;文件资产、表资产、freshness SLA、Console 聚合页后续单独扩展。

## 验收口径

- 上游没有 EFFECTIVE 版本:readiness 返回 not ready。
- DQ BLOCKED 导致 result_version PENDING:readiness 返回 not ready。
- dry-run 产生 DRY_RUN 版本:readiness 返回 not ready。
- 同 businessKey 旧 EFFECTIVE 被新 EFFECTIVE supersede 后,下游只消费当前 EFFECTIVE。
- 物化表缺历史数据时,readiness 仍可回退读取当前 EFFECTIVE result_version。
