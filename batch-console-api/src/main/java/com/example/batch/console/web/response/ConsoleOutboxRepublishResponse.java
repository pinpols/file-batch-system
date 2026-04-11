package com.example.batch.console.web.response;

public record ConsoleOutboxRepublishResponse(String tenantId, int requestedCount, int resetCount) {}
