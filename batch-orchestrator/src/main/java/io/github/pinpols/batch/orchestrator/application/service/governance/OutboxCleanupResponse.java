package io.github.pinpols.batch.orchestrator.application.service.governance;

/** Outbox 清理操作的稳定响应契约。 */
public record OutboxCleanupResponse(int published, int giveUp) {}
