package io.github.pinpols.batch.orchestrator.application.service.governance;

/** Outbox 重新发布操作的稳定响应契约。 */
public record OutboxRepublishResponse(int requested, int reset, int dryRun) {}
