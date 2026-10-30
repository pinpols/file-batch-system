package io.github.pinpols.batch.console.domain.ops.infrastructure;

/** Orchestrator 内部重新发布数量响应。 */
public record OutboxRepublishProxyResponse(int requested, int reset, int dryRun) {}
