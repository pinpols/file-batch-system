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
- `/internal/readiness/job` 保持 `ready/reason` 兼容字段,并追加 asset partition / result_version / job_instance 明细,方便 trigger 日志和运维 drill-down。

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
- 当前只支持 `asset_type=JOB`;文件资产、表资产、Console 聚合页后续单独扩展。

## Freshness Policy 告警

`asset_partition.freshness_status` 仍只表示可消费状态,当前只物化 `EFFECTIVE`。新鲜度 SLA 不把 `asset_partition` 改成 `MISSING/STALE`,而是独立写 `batch.asset_freshness_policy` 并通过 `alert_event` 告警,避免把"是否可消费"和"是否超时"混成一个字段。

策略字段:

| 字段 | 说明 |
|---|---|
| `tenant_id / asset_code / asset_type` | 当前只支持 JOB asset |
| `expected_by_local_time` | 该 asset partition 在策略时区内应完成的本地时间 |
| `timezone` | 计算 `expectedAt` 的业务时区 |
| `stale_after_seconds` | expectedAt 后的宽限期;宽限期内缺失为 MISSING,超出后为 STALE |
| `lookback_days` | 每轮回看最近多少个 bizDate,用于补捉前几天仍未完成的账期 |
| `severity` | MISSING 使用策略 severity;STALE 至少提升到 ERROR |

扫描器每轮读取启用策略:

- 当前时间早于 `expectedBy` 时不动作。
- 命中 `EFFECTIVE asset_partition` 时不告警。
- 超过 `expectedBy` 且仍无 EFFECTIVE 时写 `ASSET_FRESHNESS_MISSING`。
- 超过 `expectedBy + staleAfterSeconds` 且仍无 EFFECTIVE 时写 `ASSET_FRESHNESS_STALE`。

告警 `resourceKey` 固定为 `tenantId:assetCode:bizDate`,`detailJson` 带 `policyId/expectedAt/staleAt/breachType`。本阶段不自动 close 旧告警,因为平台既有告警模型没有自动恢复关闭契约;运维仍通过 Console 的 ACK/CLOSE 操作闭环。

## 验收口径

- 上游没有 EFFECTIVE 版本:readiness 返回 not ready。
- DQ BLOCKED 导致 result_version PENDING:readiness 返回 not ready。
- dry-run 产生 DRY_RUN 版本:readiness 返回 not ready。
- 同 businessKey 旧 EFFECTIVE 被新 EFFECTIVE supersede 后,下游只消费当前 EFFECTIVE。
- 物化表缺历史数据时,readiness 仍可回退读取当前 EFFECTIVE result_version。
- ready 响应必须带 `assetCode/bizDate/partitionKey/businessKey/freshnessStatus/versionNo/jobInstanceId`。
- 配了 freshness policy 后,缺失产物必须产生 `ASSET_FRESHNESS_MISSING` 或 `ASSET_FRESHNESS_STALE` 告警,但 readiness 仍只按 EFFECTIVE 裁决。
