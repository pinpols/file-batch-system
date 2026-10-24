package io.github.pinpols.batch.orchestrator.domain.query;

/** Low-cardinality aggregate for accepted trigger requests and recent launch latency. */
public record TriggerLaunchContinuityMetrics(
    Long staleAcceptedCount,
    Long oldestStaleAcceptedAgeSeconds,
    Long recentLaunchedCount,
    Double requestToInstanceP95Seconds) {}
