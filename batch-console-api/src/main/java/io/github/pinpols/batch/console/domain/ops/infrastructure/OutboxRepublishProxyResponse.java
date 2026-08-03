package io.github.pinpols.batch.console.domain.ops.infrastructure;

/** Internal orchestrator response for republish counts. */
public record OutboxRepublishProxyResponse(int requested, int reset, int dryRun) {}
