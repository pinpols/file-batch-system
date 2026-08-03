package io.github.pinpols.batch.console.domain.ops.infrastructure;

/** Internal orchestrator response for cleanup counts. */
public record OutboxCleanupProxyResponse(int published, int giveUp) {}
