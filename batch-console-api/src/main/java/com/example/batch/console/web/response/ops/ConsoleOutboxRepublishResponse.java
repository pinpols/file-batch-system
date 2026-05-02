package com.example.batch.console.web.response.ops;

public record ConsoleOutboxRepublishResponse(String tenantId, int requestedCount, int resetCount) {}
