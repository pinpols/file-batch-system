package com.example.batch.console.domain.query;

public record FileErrorRecordQuery(
        String tenantId,
        Long fileId,
        String errorStage,
        String errorCode,
        Boolean skipped
) {
}
