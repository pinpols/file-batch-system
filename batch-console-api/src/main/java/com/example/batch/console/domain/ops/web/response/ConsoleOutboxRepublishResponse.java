package com.example.batch.console.domain.ops.web.response;

public record ConsoleOutboxRepublishResponse(String tenantId, int requestedCount, int resetCount) {}
