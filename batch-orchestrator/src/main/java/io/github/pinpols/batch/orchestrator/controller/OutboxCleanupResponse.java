package io.github.pinpols.batch.orchestrator.controller;

/** Outbox 清理操作的稳定响应契约。 */
public record OutboxCleanupResponse(int published, int giveUp) {}
