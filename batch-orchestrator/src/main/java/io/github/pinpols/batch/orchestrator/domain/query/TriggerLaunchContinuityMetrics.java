package io.github.pinpols.batch.orchestrator.domain.query;

/** 已接受 Trigger 请求数量及近期启动延迟的低基数汇总。 */
public record TriggerLaunchContinuityMetrics(
    Long staleAcceptedCount,
    Long oldestStaleAcceptedAgeSeconds,
    Long recentLaunchedCount,
    Double requestToInstanceP95Seconds) {}
