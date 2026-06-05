# Backlog: IMPORT 分区 line-mod 切片的读放大(大文件)

> 状态:**已分析,暂不做**(无实测瓶颈,过早优化)。本条记录触发条件 + 推荐做法,真遇到大文件吞吐问题时直接照办。
> 日期:2026-06-05　模块:batch-worker-import

## 现象 / 根因
`ParseStep` 的默认分区切片是**按行 mod**:`lineNo % partitionCount == partitionNo - 1`,
每个 partition **流式扫一遍 staging 文件、只保留属于自己的行**(`partition_aware_parse`,默认开)。
→ N 片 = **N 次全文件读放大**。10G 文件切 20 片 ≈ 200G 读。

正确性没问题(流式、内存有界),纯粹是**大文件 + 多分区时的 I/O 放大**。

## 为什么暂不做
- 目前 seed/真实文件都很小(KB 级),**没有任何"大文件吞吐打不住"的实测证据** → 为假设做重活是过早优化。
- 字节-range 物理切**高复杂度 + 易错**:必须对齐行/记录边界(CSV 引号内嵌换行、定宽、多字节编码边界截断、header/footer);且**格式相关**(Excel/JSON 根本不能 byte-range 切)。

## 现成缓解(无需开发,先用这些)
1. `targetBytesPerPartition` 调大 → 片数变少(10G 切 2-4 片而非 20)→ 读放大降到 2-4×。
2. `template_config.partition_aware_parse=false` → 单片单流处理,**零读放大**(不并行,但大文件本就 I/O 受限,单流流式 + 内存有界可接受)。

## 触发条件(满足才做)
- 出现**实测**的大文件(≥ 数 GB)导入吞吐瓶颈,且上面两个旋钮调过仍不达标。

## 推荐做法(届时)
- **不要**每片 byte-range seek。改用**一次性 split step**:先按**行边界**把大文件物理切成 N 个子文件(读 1 遍),每片读自己的子文件(再读 1 遍)→ 总 **2× 读**(而非 N×)。
- **只对 DELIMITED / FIXED_WIDTH 做**;Excel/JSON 不切(保持单片)。
- 由 benchmark 驱动验证收益,再合入。

## 关联
- `batch-worker-import/.../stage/ParseStep.java`(line-mod 切片)
- `SizeBasedPartitionCountResolver`(片数 = 字节数 ÷ targetBytesPerPartition)
- `ShardStrategy` / ADR-005(分区数量决策链)
