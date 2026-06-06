# Backlog: EXPORT 分片 hashtext 全扫放大 → keyset 区间分片优化(方案 A)

> 状态:**已设计,待实测瓶颈驱动**(与 import Range 下载对称的导出侧放大优化)。
> 日期:2026-06-06　模块:batch-worker-export、batch-common
> 对照:[import-line-mod-read-amplification-2026-06-05.md](import-line-mod-read-amplification-2026-06-05.md)(导入侧同类放大,已实现 Range 下载)
> 前置:[export-partition-slice-fix-2026-06-06.md](export-partition-slice-fix-2026-06-06.md)(导出 partition-aware 分片治本,本文是其放大优化)

## 1. 放大点 / 根因

导出 partition-aware 分片(已实现)用 **hashtext 取模谓词**在用户 base 查询外层筛 1/N:

```sql
WITH base AS ( <用户 default_query_sql> )
SELECT * FROM base
WHERE ((hashtext(base.<cur>::text) % :count) + :count) % :count = :idx
  [AND base.<cur> > :__cursor]
ORDER BY base.<cur> ASC LIMIT :__limit
```

**`hashtext(col)` 不走索引** → 每个分区都要把 base **完整扫一遍**、对每行算 hashtext、只留 1/N。

| 环节 | 单分片 | N 分片合计 |
|---|---|---|
| base 表扫描 | 全量 | **N×** |
| hashtext 计算 | 全量 | **N× CPU** |
| 最终写出 | 1/N | 1×(行不重) |

**为什么导入的 Range 下载这招用不上**:导入源是「可按字节偏移切的文件」(`GetObjectArgs.offset`);
导出源是「一条 SQL」,结果集无法按字节切。降放大只有一条路:**让每片在 DB 侧只读自己那 1/N,而非全读再筛**。

## 2. 激活条件(否则一律退回 hashtext)

keyset-range 是**按需优化**,默认行为不变。全满足才启用:

1. `partitionCount > 1`(单片无意义)
2. 模板 opt-in:`partition_keyset_range = true`(用户声明游标列可排序、有索引、range-friendly)
3. 游标列为**可排序类型**(数值 / 时间)且**有索引**

任一不满足 → **退回现有 hashtext**(通用、零边界计算、保正确性)。即「大数据 + 高分片数 + 合适游标列」才走 keyset-range,其余场景走原路。

## 3. 设计:keyset 区间分片(与 import range-slice 对称)

### 3.1 核心对称点:每分区自算区间,不经 orchestrator

import 的 range-slice 是**每个分区独立**从 `statObject` 拿对象字节数,算自己的 `[rawStart, rawEnd)`:
```
rawStart = objectBytes * (partitionNo-1) / partitionCount
rawEnd   = objectBytes *  partitionNo    / partitionCount
```
**全程不经 orchestrator**(`PreprocessStep.java:524`)。

导出照搬:**每个分区独立**用游标列的 `min/max` 算自己那段值区间。
> ❌ **不走 orchestrator 注入边界**:`SizeBasedPartitionCountResolver` 当前从 `params`(预估条数/字节)算片数,
> **不查业务库**。让 orchestrator 算边界 = 把用户导出 SQL 的执行 + SQL 校验(`SqlTemplateExportSqlValidator`)
> + RLS 重做进 planner,新耦合 + 安全面重复,与「worker plugin 解释切分维度」(ADR-005)冲突。否决。

### 3.2 边界计算(每分区一次,廉价)

```sql
SELECT min(<cur>) AS lo, max(<cur>) AS hi FROM ( <用户 base SQL> ) base;
```
游标列有索引且 base 简单(无聚合/分组)时,PG 对 min/max 走索引(`Index Only Scan`,~2 次 B-tree 探查,接近 O(1))。
N 个分区各跑一次这个**廉价**查询 → 远小于被消除的 N× 全扫。

等宽切分本片闭开区间:
```
width = (hi - lo) / partitionCount            -- 数值;时间用 epoch 差等价处理
loN = lo + width*(partitionNo-1)
hiN = (partitionNo==partitionCount) ? hi+ε : lo + width*partitionNo
```

### 3.3 分片谓词(替换 hashtext)

```sql
WITH base AS ( <用户 default_query_sql> )
SELECT * FROM base
WHERE base.<cur> >= :__loN AND base.<cur> < :__hiN    -- 末片用 <= 含上界
  [AND base.<cur> > :__cursor]                        -- 原 keyset 翻页,保留
ORDER BY base.<cur> ASC LIMIT :__limit
```
游标列有索引 → 每片走**索引区间扫**,只读本片 1/N。放大 N× → ~1×(外加 N 次廉价 min/max)。

正确性:N 段值区间互不相交(`[loN,hiN)` 无缝拼接)且并集 = `[lo,hi]` 全集 → 无重无漏,是 import
`lineNo % count` 的「值域切分」等价物,履约 ADR-005。

## 4. 倾斜处理

- **等宽切**(默认):游标列分布不均时各片行数不均(如 id 有大段删除空洞)。可接受 —— 与 import range-slice
  按字节等分也会有行数倾斜一致;分片目的是「并行换吞吐」,非严格均分。
- **分位切**(未来 opt-in,YAGNI):严重倾斜场景再加 `partition_keyset_mode=percentile`,用
  `percentile_disc(ARRAY[...]) WITHIN GROUP (ORDER BY cur)` 算分位边界(1 次全扫算边界,仍远优于 N×)。
  **本期不做**,留扩展位。

## 5. 组件改动(叠加在 #390 之上,不重写)

| # | 文件 | 改动 |
|---|---|---|
| 1 | `SqlTemplateExportDataPlugin` / `GenericJdbcMappedExportDataPlugin` | `loadDetailPage`:命中激活条件时,先 min/max 算 `[loN,hiN)`,谓词由 hashtext 换 keyset 区间;否则保持 hashtext |
| 2 | 边界算一次/分区 | 首页(`cursor==null`)时算 `[loN,hiN)` 存入 plugin 调用上下文,后续翻页复用,避免每页重算 |
| 3 | 模板配置 | `partition_keyset_range`(bool, opt-in)+ 预留 `partition_keyset_mode`(equal/percentile) |
| 4 | SQL 校验 | min/max 探测 SQL 复用现有 `SqlTemplateExportSqlValidator`(仍是只读 SELECT,白名单内) |
| 5 | guard / fallback | 游标列非可排序 / 无 min/max(空结果)/ 任何异常 → **退回 hashtext**,优化绝不导致导出失败 |

接口 `ExportDataPlugin.loadDetailPage` 签名**不改** —— partition 信息已走 `ExportDataContext`(#390),
keyset 边界是 plugin 内部计算,不外溢。

## 6. 向后兼容 / 不做(YAGNI / 越界)

- **向后兼容**:未 opt-in / 单片 / 游标列不合适 → 逐字节维持现有 hashtext 行为。
- **不做**:① 分位切倾斜优化(留扩展位,实测倾斜瓶颈再做);② orchestrator 注入边界(架构否决,见 3.1);
  ③ 跨分区共享边界的「规划前置 step」(需 fan-in,与独立分区架构冲突,见 export-partition-slice-fix)。

## 7. 测试

对照 `ParseStepPartitionSliceTest` / `ExportPartitionSliceIT`:
- **区间完整性**:M 行表 opt-in keyset-range,`partitionCount=4` 跑 4 片,断言 4 片行集无重叠且并集=全部 M 行
  (覆盖 sql_template + jdbc_mapped 两 plugin)。
- **退回 hashtext**:未 opt-in / 游标列为文本 / min-max 空 → 断言走 hashtext 谓词且结果正确。
- **倾斜**:故意造空洞分布,断言仍无重无漏(允许各片行数不均)。
- **边界只算一次**:断言一次导出内 min/max 探测 SQL 只执行一次/分区(非每页)。
- 约定:JUnit5 + AssertJ + Mockito;IT 继承 `AbstractIntegrationTest`。

## 8. 触发条件(满足才实现)

- 出现**实测**的大数据(≥ 千万行)+ 高分片数(≥ 8)导出场景,且 hashtext N× 全扫被 benchmark 证明为瓶颈。
- 现状:`export-partition-slice-fix` 已把正确性治本,放大「已接受」;无实测瓶颈前实现本优化 = 重演 import backlog
  警告的「为假设做重活」。**先 benchmark,后实现。**

## 9. 关联

- `batch-worker-export/.../plugin/{SqlTemplateExportDataPlugin,GenericJdbcMappedExportDataPlugin}.java`(谓词所在)
- `batch-worker-import/.../stage/PreprocessStep.java`(`streamObjectRangeToSpool` 每分区自算区间的对照实现)
- `batch-common/.../plugin/ExportDataContext.java`(partition 通道,#390 已加)
- `ShardStrategy` / ADR-005(切分维度由 worker plugin 解释)
- 分支:业务模块代码 → `feature/<topic>` 标准分支 → PR → main(非部署分支)
