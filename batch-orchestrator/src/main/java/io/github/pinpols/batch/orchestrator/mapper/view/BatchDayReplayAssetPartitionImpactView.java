package io.github.pinpols.batch.orchestrator.mapper.view;

/**
 * batch-day replay 预览中的资产分区影响投影。
 *
 * <p>该类型将固定 SQL 列约束在 Mapper 边界，避免重放预览在应用层依赖字符串键读取数据库结果。
 */
public record BatchDayReplayAssetPartitionImpactView(
    String businessKey,
    String assetCode,
    String partitionKey,
    Long currentResultVersionId,
    String freshnessStatus) {}
