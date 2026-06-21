# ADR-024 · 冷热数据分层 + 长保留

- **Status**: Accepted（**第 3 阶段 / P2 暂缓**，数据量 / 备份 / 监管阈值到了再开工 — 见"实施触发条件"）
- **Date**: 2026-05-06
- **Supersedes**: 部分超越 §archive 冷表对齐基线（仍兼容）
- **Related**: ADR-022（forensic 长保留）/ §archive 冷表对齐 / §14.3.2 / [ADR 012/021-027 优先级 + 范围边界](../../analysis/adr-012-021-027-priority-scope-2026-05-06.md)

## 范围边界（Scope Discipline）

> **暂缓。**当前先做轻量保留策略（保留天数 / 按 biz_date 清理 / 删除前 manifest），完整冷热分层 + OSS Parquet 等阈值触发再做。**绝不做完整数据湖架构。**

| ✅ 触发条件到了再做 | ❌ 当前先做的轻量基线（不属本 ADR） | ❌ 绝不做 |
|---|---|---|
| 14 张 archive 表 PG 月分区 | 大表带 `business_date` / `created_at` + 合理索引 | 完整数据湖（Iceberg / Delta Lake，过度工程） |
| DETACH PARTITION + OSS Parquet 卸载 | 日志类表保留天数配置 | TimescaleDB / ClickHouse 双引擎（运维多一套） |
| `archive_storage_metadata` + sha256 attestation | 删除前写 audit log | v1 不做 OSS 跨区域复制（DR 另算） |
| DuckDB embedded 冷查询 + 双源合并 | 预留 `archive_storage_metadata` 列名 / partition 切分键 = `biz_date`（防未来改不动） | 冷数据"按需重新 attach 回 PG"（重新加载成本 > 直接查 OSS） |

## 背景

当前 `archive.*_archive` 14 张镜像表是**热 PG 库里另一张表**，本质是同一个 PG 实例 / 同一个 disk。

业务现实：

- 监管 / 合规要求 7-15 年保留（银行交易明细）；
- 平台跑 5 年后 `job_instance_archive` 行数预计 ≥ 100 亿，PG 单表性能 / 备份 / 索引重建都崩；
- 历史查询 90% 是"按 bizDate 拉某天的全部 instance" → 不需要事务能力，纯 OLAP；
- 但活跃运营关注的"最近 30 天 partition 性能" 又必须留在 PG 上（强一致 / 索引 / FK）。

业界（金融 / 电商 / 平台型批量）都用 **三层存储**：

| 层 | 介质 | 保留期 | 查询特征 |
|---|---|---|---|
| **HOT** | PG 主库 | 30-90 天 | 强一致、低延迟、索引、JOIN |
| **WARM** | PG 归档 schema / 独立读副本 | 6-12 月 | 弱实时、分区扫描 |
| **COLD** | OSS / S3 + Parquet | 7-15 年 | 按 bizDate 范围拉、列式、外部 query 引擎 |

## 决策

引入分层架构 + PG declarative partition + 异步 cold-storage 卸载：

### 核心改动

1. **archive 表全部按 bizDate 做 PG declarative partition**：

```sql
-- 现状
CREATE TABLE archive.job_instance_archive (LIKE batch.job_instance INCLUDING ALL);

-- 改为
CREATE TABLE archive.job_instance_archive (
  ...
  biz_date DATE NOT NULL
) PARTITION BY RANGE (biz_date);

-- 按月分区
CREATE TABLE archive.job_instance_archive_2026_05
  PARTITION OF archive.job_instance_archive
  FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
```

`ArchiveSchemaDriftCheck` 同步保护：检查 partition 父表 + active partition 列对齐。

2. **冷数据卸载到 OSS Parquet**：

```sql
batch.archive_storage_metadata
  id              BIGSERIAL PK
  table_name      VARCHAR(64)        -- 'job_instance_archive'
  partition_name  VARCHAR(128)       -- '2026_05'
  oss_path        VARCHAR(512)       -- oss://batch-cold/{tenant}/job_instance/2026/05/...
  row_count       BIGINT
  byte_count      BIGINT
  sha256          VARCHAR(64)
  detached_at     TIMESTAMPTZ
  retention_until DATE
```

`ArchiveTieringScheduler` 定期：

```
每月 1 日 03:00 (按 calendar)
  ├── 找出 archive.* 中超过 WARM_RETENTION_MONTHS（默认 12）的最早 partition
  ├── COPY ... TO PROGRAM 'parquet-tools' → OSS（流式，不进 worker 内存）
  ├── 写 archive_storage_metadata + sha256 attestation
  ├── DETACH PARTITION（保留物理表 30 天 grace 后 DROP，防回滚需求）
  └── 写 audit log
```

3. **冷查询走"hybrid view"**：

为常用查询提供 SPI：

```java
public interface ArchiveQueryService {
  /** 按 bizDate 范围查 — 自动决策走 PG / OSS / 双源合并。 */
  List<JobInstanceEntity> queryByDateRange(
      String tenantId, String jobCode, LocalDate from, LocalDate to);
}
```

实现策略：

- 范围全在 HOT/WARM 区间 → PG；
- 范围全在 COLD → 拉 OSS Parquet（用 DuckDB / Apache Calcite / 项目自带 Parquet reader）；
- 跨区间 → 两边都查，应用层合并（按 bizDate / id 排序）。

### 三层默认参数

```yaml
batch.archive.tiering:
  hot-retention-days: 90        # 留在 batch.* 主表
  warm-retention-months: 12     # 留在 archive.* PG 分区表
  cold-retention-years: 7       # OSS Parquet 保留
  scan-cron: "0 0 3 1 * ?"      # 每月 1 日 03:00
  parquet-compression: zstd     # 平衡压缩比 / 解压速度
```

## 影响面

| 维度 | 影响 |
|---|---|
| 持久层 | 14 张 archive 表全部 partition 化（破坏性 DDL，需要 maintenance window 迁移）+ 1 张 metadata 表 |
| 模块 | 新 `ArchiveTieringScheduler` + `OssParquetWriter` + `ArchiveQueryService` SPI；`ArchiveSchemaDriftCheck` 适配 partition |
| 性能 | 写入零影响（archive 写仍走主分区路由）；冷查询延迟从 ms → 秒级，但 P99 仍可接受 |
| 存储 | PG 占用从指数膨胀变线性（1-2 年内）；OSS 成本约 $0.02/GB/月，相比 PG SSD 节省 ~10-20x |
| 兼容 | 现有 archive 表查询 SQL 不动（partition 父表透明）；只有冷查询 SPI 是新接入 |

## 实施分阶段

| Stage | 范围 | 估算 |
|---|---|---|
| 1 | 14 张 archive 表 partition 改造（带数据迁移脚本） | 5 天（含演练） |
| 2 | `ArchiveTieringScheduler` + DETACH PARTITION 流程 | 4 天 |
| 3 | OSS Parquet writer + sha256 + 上传重试 | 3 天 |
| 4 | `ArchiveQueryService` SPI + 双源合并 | 4 天 |
| 5 | DuckDB / Calcite 集成（OSS Parquet 直查） | 3 天 |
| 6 | `ArchiveSchemaDriftCheck` 适配 partition + metadata 一致性 | 2 天 |
| 7 | runbook + cold restore 演练（监管查时如何快取出来） | 2 天 |

总 ~23 人天（含 partition 迁移演练）。

## 替代方案

| 方案 | 拒绝 |
|---|---|
| 走 TimescaleDB hyper-partition | 引入第三方扩展，PG 版本绑死，运维复杂 |
| 用 ClickHouse 做长保留 | 双引擎、跨引擎一致性难保证、运维多一套 |
| 仅靠 PG 物理 vacuum + dump 备份 | 不能查询、恢复要全表 dump、无 partition pruning |
| 完全走 OSS（不分层） | 实时查询性能受不了，秒级 → 业务方投诉 |

## 不变量

1. **冷数据上传后 PG partition 必经 30 天 grace 才物理 DROP**（防回滚 / 抽查）；
2. OSS 对象上传必带 sha256，metadata 表存 hash + 行数 + 字节数三件套，可独立校验；
3. 任何冷查询永远不写 OSS（只读）；
4. partition 切分键固定为 `biz_date`，不允许后续改（破坏所有 detach 流程）；
5. `archive_storage_metadata` 是冷数据唯一索引 — 丢这张表 = 丢冷数据可发现性，必须主备 + 跨区域备份；
6. partition 父表与 batch.* 主表 schema 漂移时 `ArchiveSchemaDriftCheck` 仍能工作（v1 兼容）。

## 验收

- 单测：partition routing / parquet round-trip / sha256 / 双源合并排序
- IT：1 个月数据 detach + 上传 + 冷查询命中率
- 演练：模拟监管来查"7 年前某 bizDate 全部 job"端到端时间 < 30 分钟
- 守护：`ArchivePartitionInvariantTest`（partition 一致性 + metadata 一致性）

## 实施触发条件

满足任一：
1. **数据量阈值**：`archive.job_instance_archive` 行数 ≥ 5 亿，或单表大小 ≥ 500 GB；
2. **保留要求**：监管 / 合规要求 ≥ 5 年保留；
3. **性能恶化**：archive 表查询 P99 > 5 秒持续 14 天；
4. **备份痛点**：PG 全量 dump 时间 > 4 小时（影响维护窗口）。

数据量小 + 保留期短的项目继续用现有热 archive 表，本 ADR 不开工。

## 开放问题（已收敛）

| # | 问题 | 决策 |
|---|---|---|
| 1 | partition 粒度（月 / 周 / 日） | **月**。日粒度 partition 数量过多 PG planner 慢；周不和业务自然对齐；月最优 |
| 2 | OSS 选 Parquet 还是 ORC | **Parquet**。生态最广（Spark / DuckDB / Trino / Athena）；ORC 优势在 Hive 生态，本系统不靠 |
| 3 | 冷查询用什么引擎 | v1 用 DuckDB embedded（零运维、单文件 jar）；规模上来切 Trino/Presto |
| 4 | DETACH 后 30 天 grace 怎么管理 | DETACH 后表名加 `_pending_drop_<date>` 后缀；定期 scheduler drop 过期的 |
| 5 | OSS 对象 immutable 吗 | **是**。启用 OSS 对象锁（compliance mode），防误删 / 篡改；与 ADR-022 forensic 同策略 |

### 不会做

- ❌ 不引入完整数据湖架构（Iceberg / Delta Lake）；过度工程，本场景不需要 ACID over object store
- ❌ v1 不做 OSS 跨区域复制（DR 场景另算）
- ❌ 不让冷数据"按需重新 attach 回 PG"（重新加载成本 > 直接查 OSS）
- ❌ 不允许冷数据缺 partition 而 metadata 显示存在（drift check fail-fast）
