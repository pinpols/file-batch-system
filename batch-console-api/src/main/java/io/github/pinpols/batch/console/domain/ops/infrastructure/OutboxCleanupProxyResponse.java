package io.github.pinpols.batch.console.domain.ops.infrastructure;

/** Orchestrator 内部清理数量响应。 */
public record OutboxCleanupProxyResponse(int published, int giveUp) {}
