package io.github.pinpols.batch.orchestrator.controller;

/** Outbox 重新发布操作的稳定响应契约。 */
public record OutboxRepublishResponse(int requested, int reset, int dryRun) {}
