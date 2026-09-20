package io.github.pinpols.batch.console.domain.ops.application.contract.response;

public record ConsoleOutboxRepublishResponse(String tenantId, int requestedCount, int resetCount) {}
