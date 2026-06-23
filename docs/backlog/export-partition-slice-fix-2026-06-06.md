# Backlog: EXPORT 分区不切数据导致重复/丢数据(治本设计)

> 状态:**已实现**(分支 `feature/export-partition-slice`;5 个实现 task + 分片完整性 IT 全部通过)。补齐 import 早有的分片切数据能力。
> 日期:2026-06-06　模块:batch-worker-export、batch-common
> 对照:[import-line-mod-read-amplification-2026-06-05.md](import-line-mod-read-amplification-2026-06-05.md)

## 现象 / 根因

orchestrator 侧正确按 `shardStrategy` 切出 N 个 partition,并把 `PARTITION_NO`/`PARTITION_COUNT`
注入 executionContext(`DefaultTaskExecutionWrapper`)。**但 export worker 侧整条链从不消费这两个值**:

- `ExportDataContext`(`batch-common/.../plugin/ExportDataContext.java`)record 没有 partition 字段。
- `SqlTemplateExportDataPlugin.loadDetailPage` 拼的 SQL 没有任何分片谓词(`buildPagedSql`
  只做 CTE 包装 + keyset 翻页)。
- `GenericJdbcMappedExportDataPlugin.loadDetailPage` 同样只按 `batchId` 锁定,无分片谓词。
- 整个 `batch-worker-export` 模块 grep `partitionNo` → 零匹配。

→ N 个 partition 各跑**同一条全量 SQL**:

| 层面 | 后果 |
|---|---|
| 数据库 | N× 全量读 |
| 文件生成 | 每片各生成一份全量文件 |
| MinIO 落地 | `objectName`/`tempObjectName` 不含 partitionNo → N 片写**同一路径**互相覆盖(并发竞态,潜在损坏);若未来文件名带 partitionNo 则变 N 份重复文件 |
| `file_record` | 一个 batch 注册 N 条,`recordCount` 单倍 |

正确性 bug,非单纯放大。触发条件:export job `shardStrategy=STATIC/DYNAMIC` 且 `partitionCount>1`。

## 与 import 分片的关系(对称性)

import 的**分片切数据早已做且默认开**:`ParseStep` 的 `lineNo % count == partitionNo-1`
(2026-05-01 hardening,`ParseStepPartitionSliceTest` 守护)。相邻 backlog 暂不做的,是它的
**读放大优化**(把 line-mod 的 N× 文件读用「一次性物理 split」降到 2×)—— 那是锦上添花的
I/O 优化,**不是分片能力本身**。

| | import | export(本条) |
|---|---|---|
| 分片切数据 | ✅ 已做(line-mod,默认开,有守护测试) | ❌ **没做**(本条 bug,N× 重复/可能丢数据) |
| 切片的放大优化 | ⏸ backlog 暂不做(读放大,过早优化) | 同样留 backlog(hashtext 全扫的计算放大,见下) |

所以 export 治本 = **补齐 import 早就有的「分片切数据」能力**,完全对称、必要;不是做什么
import 都不做的超前优化。两边的「放大优化」一致地留 backlog,待实测瓶颈驱动。
且 export 确有千万行级对账文件并行导出提速的需求,故走完整 partition-aware,而非仅 guard 止血。

## 设计方案:partition-aware + N 个分片文件

### 数据流
```
executionContext{PARTITION_NO, PARTITION_COUNT}   (orchestrator 已注入)
  ↓ GenerateStep 读出注入
ExportDataContext{..., partitionNo, partitionCount}
  ↓ plugin 据此加分片谓词
loadDetailPage: WHERE <shard> AND <keyset翻页> → 只取本片 1/N
  ↓ PrepareStep 文件名带 partitionNo
objectName = base-part{NO}of{COUNT} → 各片独立文件,不再覆盖
  ↓
每片各注册一条 file_record(指向各自 1/N 分片文件)
```

### 产物形态:N 个分片文件(非合并单文件)
每片输出独立文件、各注册一条 `file_record`。理由:
- 与现有「每个 partition task 完全独立、无 fan-in」架构对称,与 import 各片独立写库对称。
- `file_record` 本就一 batch 多条,治本后这 N 条各指向正确的 1/N 文件,数据模型零改动。
- 合并单文件需引入 fan-in barrier + 下载 N 片拼接重传(2× IO + OOM 风险,即
  [streaming-large-file](../verifications/streaming-large-file-import-export-2026-06-06.md) 已记录的问题),
  且 CSV header / JSON array / 定长对齐使「合并 ≠ cat」。**单文件交付作为独立可选 post-merge feature 另案,不进本次。**

### 组件改动
| # | 文件 | 改动 |
|---|---|---|
| 1 | `batch-common/.../plugin/ExportDataContext.java` | record 加 `int partitionNo` / `int partitionCount`(默认 1/1) |
| 2 | `batch-worker-export/.../stage/GenerateStep.java` | `buildExportDataContext()` 从 attributes 读 `PARTITION_NO`/`PARTITION_COUNT` 注入(缺省 1/1) |
| 3 | `.../plugin/SqlTemplateExportDataPlugin.java` | `buildPagedSql()` 在 CTE 外层叠加分片谓词(仅 `partitionCount>1`) |
| 4 | `.../plugin/GenericJdbcMappedExportDataPlugin.java` | `loadDetailPage()` WHERE 同样叠加分片谓词(按排序列/主键) |
| 5 | `.../stage/PrepareStep.java` | `resolveObjectName`/`fileName`/`tempObjectName`:`partitionCount>1` 加 `-part{NO}of{COUNT}` 后缀;`==1` 文件名不变(向后兼容) |
| 6 | (3)(4) 内 | guard:`partitionCount>1` 但无可分片列 → fail-fast,不静默退单片 |

接口 `ExportDataPlugin.loadDetailPage` 签名**不改** —— partition 信息走 `ExportDataContext`,2 个实现都能读。

### 分片谓词(核心)
在现有 CTE 外层叠加,不碰用户 SQL:
```sql
WITH base AS ( <用户 default_query_sql> )
SELECT * FROM base
WHERE ((hashtext(base.<cur>::text) % :__partCount) + :__partCount) % :__partCount = :__partIdx
  [AND base.<cur> > :__cursor]          -- 原 keyset 翻页,保留
ORDER BY base.<cur> ASC
LIMIT :__limit
```
正确性:每行 `hash % count` 唯一落入一个 `idx∈[0,count)` → N 片互不相交且并集=全集,
是 import `lineNo % count == partitionNo-1` 的 SQL 侧等价物,履约 ADR-005「切分维度由 worker plugin 解释」。

健壮性:
- 用 `((h % count) + count) % count` 而非 `abs(hashtext(...))`,避免 `abs(INT_MIN)` 溢出。
- 统一 `hashtext(col::text)` 支持任意类型分片列。代价:hashtext 不走索引、每片全扫一遍 base
  (即「计算放大」,已接受;真正零重算需把分片下推进任意用户 SQL,不安全,不做)。

### 错误处理 / 边界
- **向后兼容**:`partitionCount==1` 时分片谓词不注入、文件名不加后缀,单片行为逐字节不变。
- **fail-fast**:`partitionCount>1` 但无可分片列 → 抛明确 `BizException`,不静默降级。
- **不做(YAGNI/越界)**:① 单一合并文件(另案 post-merge);② 整数列下推优化;
  ③ 跨 PG 大版本 hashtext 稳定性(同次导出 N 片同库同版本,一致性天然成立)。

## 测试
对照 `ParseStepPartitionSliceTest`:
- **分片完整性**:M 行表,`partitionCount=4` 跑 4 次 `loadDetailPage`,断言 4 片行集无重叠
  且并集=全部 M 行(覆盖 sql_template + jdbc_mapped 两 plugin)。
- **文件命名**:`partitionCount>1` 时 objectName/tempObjectName 含 `-part{NO}of{COUNT}` 且各片唯一;`==1` 不含后缀。
- **guard**:`partitionCount>1` 且无可分片列 → 抛预期异常。
- 约定:JUnit5 + AssertJ + Mockito;IT 继承 `AbstractIntegrationTest`。

## 关联
- `batch-common/.../plugin/ExportDataContext.java`、`ExportDataPlugin.java`
- `batch-worker-export/.../stage/{GenerateStep,PrepareStep}.java`
- `batch-worker-export/.../plugin/{SqlTemplateExportDataPlugin,GenericJdbcMappedExportDataPlugin}.java`
- `batch-worker-import/.../stage/ParseStep.java`(line-mod 对照实现)、`ParseStepPartitionSliceTest`
- `ShardStrategy` / ADR-005(分区数量决策链)
- 分支:全部业务模块代码 → `feature/<topic>` 标准分支,不涉及部署分支
