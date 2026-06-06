# Backlog: IMPORT 分区 line-mod 切片的资源放大(大文件)

> 状态:**✅ 已实现(2026-06-06,Range 下载方案)**。下文"现象/根因/缓解"保留作背景;实现见末尾「实现记录」。
> 日期:2026-06-05　模块:batch-worker-import
> **订正 2026-06-06**:原文只盯住 parse 扫描的"读放大",实测代码后发现放大是**三段**(下载 / 解析 / 扫描)都 N×;
> 且 `partition_aware_parse=false` 缓解项有正确性前提(必须配 partitionCount=1),原文未点破。下文已更新。

## 现象 / 根因

分片是 **orchestrator 端 fan-out**:`SizeBasedPartitionCountResolver` 按 `估算字节 ÷ targetBytesPerPartition`
算出 N → 建 N 个独立 `job_partition` → N 条 Kafka 消息 → N 个 worker 各自 CLAIM,**每个都跑完整 pipeline**
(Preprocess → Parse → Validate → Load)。分片间不共享本地文件(可能落在不同主机)。

每个分片 worker 实际做的:

1. **Preprocess**:由 `importPayload.storagePath()` 驱动,各自从对象存储**下载整份**源文件 + spool 落盘
   (`PreprocessStep.java` 大文件流式直载)。
2. **Parse**:`parsePayloads` 把**整份** payload 解析进 staging(`ParseStep.java:111`)。
3. **applyPartitionFilter**:再把整份 staging 流式扫一遍,只留 `lineNo % partitionCount == partitionNo-1`
   的行(`ParseStep.java:420`,`partition_aware_parse` 默认开)。

→ N 片 = **下载 N× + 解析 N× + 扫描 N×**,只有最终 Load 是 1/N(行不重复)。
正确性没问题(流式、内存有界),纯粹是**大文件 + 多分区时的资源放大**:并行换吞吐,代价是前三段全量重复。

| 环节 | 单分片 | N 分片合计 |
|---|---|---|
| 对象存储下载 | 全量 | **N×** |
| 解码 / 解密 / 解析 | 全量 | **N× CPU** |
| parse 扫描过滤 | 全量 | **N× 本地读** |
| 最终 Load | 1/N | 1×(行不重) |

例:10G 文件切 20 片 ≈ 20× 下载 + 20× 解析 + 20× 扫描。

## 为什么暂不做

- 目前 seed/真实文件都很小(KB 级),**没有任何"大文件吞吐打不住"的实测证据** → 为假设做重活是过早优化。
- 字节-range 物理切**高复杂度 + 易错**:必须对齐行/记录边界(CSV 引号内嵌换行、定宽、多字节编码边界截断、
  header/footer);且**格式相关**(Excel/JSON 根本不能 byte-range 切)。

## 现成缓解(无需开发,先用这些)

1. **`targetBytesPerPartition` 调大** → 片数变少(10G 切 2-4 片而非 20)→ 三段放大都降到 2-4×。
   这是**首选**旋钮:它同时压下下载/解析/扫描三段,且不改变行分摊语义(每片仍只 Load 自己那份)。
2. **`template_config.partition_aware_parse=false`** → 单流处理,扫描段零放大。
   **⚠ 正确性前提:必须同时把分片数压回 1**(`targetBytesPerPartition` 调到比文件还大,或显式
   `partitionCount=1`)。否则 orchestrator 仍按文件大小 fan-out N 片,而每片关掉过滤后**保留全部行**
   → N 个分片各自 Load 全量 → **N× 重复写**,只靠 `ON CONFLICT` 去重兜底(浪费 + 依赖幂等)。
   故此项**不是**独立优化,而是"放弃并行、退回单流"的开关,要与片数=1 成对使用。

## 触发条件(满足才做)

- 出现**实测**的大文件(≥ 数 GB)导入吞吐瓶颈,且上面两个旋钮调过仍不达标。

## 推荐做法(届时)

- **不要**每片 byte-range seek。改用**一次性 split step**:先按**行边界**把大文件物理切成 N 个子文件(读 1 遍),
  每片读自己的子文件(再读 1 遍)→ 总 **2× 读**(而非 N×),且各片下载量也降到 1/N(切分产物各自独立)。
- **只对 DELIMITED / FIXED_WIDTH 做**;Excel/JSON 不切(保持单片)。
- 由 benchmark 驱动验证收益,再合入。

## 关联

- `batch-worker-import/.../stage/PreprocessStep.java`(每片各自下载整份)
- `batch-worker-import/.../stage/ParseStep.java`(全量解析 + line-mod 切片过滤)
- `batch-orchestrator/.../plan/SizeBasedPartitionCountResolver.java`(片数 = 字节数 ÷ targetBytesPerPartition)
- `ShardStrategy` / ADR-005(分区数量决策链)

## 实现记录(2026-06-06)

最终选 **Range 下载**(非原文推荐的 split 子对象)。原因复盘:split 子对象需 orchestrator 两阶段派发
(worker 不能写 partition 表 → 异步 event loop + 中间状态),与"单一状态主机"冲突且耦合更深;而 MinIO SDK
原生支持 range GET(`GetObjectArgs.offset/length`,S3 标准原语),改动只局限 PreprocessStep,放大 ~1×
(下载+解析都降,优于 split 的 2×)。

- **机制**:`PreprocessStep` 在 ≥16MB 直载分支再分流:多分片 + 安全格式 + UTF-8 兼容字符集时,
  `streamObjectRangeToSpool` 用 range GET 只下本片 `[rawStart,rawEnd)` 字节,`copyPartitionRange`
  按行边界对齐(标准 split 法:跳残首行 + 补齐跨界末行,无重叠无遗漏)。置 `PARTITION_PRESLICED`,
  `ParseStep` 见到即跳过 line-mod。
- **安全边界**:FIXED_WIDTH(物理行=记录)自动启用;DELIMITED 走 Univocity RFC4180 支持引号内嵌跨行 →
  默认不切,仅模板 `partition_range_slice=true` opt-in;JSON/XML/EXCEL/压缩/加密 回退现状。
- **fallback**:statObject 失败 / range 任何异常 → 回退整份直载,优化绝不导致导入失败。
- **零行为变化面**:小文件(<16MB)/ 单片 / 非安全格式 全维持现状。
- 验证:`PreprocessRangeSliceTest`(13)+ `ParseStepPartitionSliceTest`(5,含 preslice 跳过)。

> 注:本实现与导出分片(`feature/export-partition-slice`)同分支提交(并行开发合流),不单独 PR。
