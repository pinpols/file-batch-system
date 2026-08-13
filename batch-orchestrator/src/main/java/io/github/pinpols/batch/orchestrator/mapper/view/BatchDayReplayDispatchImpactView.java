package io.github.pinpols.batch.orchestrator.mapper.view;

/**
 * batch-day replay 预览中的历史派发聚合投影。
 *
 * <p>计数均由 SQL 聚合产生；使用具名字段可使预览响应与查询列在编译期对齐。
 */
public record BatchDayReplayDispatchImpactView(
    Long sourceInstanceId,
    Long recordCount,
    Long sentCount,
    Long failedCount,
    Long pendingReceiptCount) {}
