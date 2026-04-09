package com.example.batch.console.web.response;

public record ConsoleOutboxCleanupResponse(
        String tenantId,
        int retainDays,
        int deletedPublished,
        int deletedGiveUp
) {
    public int totalDeleted() {
        return deletedPublished + deletedGiveUp;
    }
}
