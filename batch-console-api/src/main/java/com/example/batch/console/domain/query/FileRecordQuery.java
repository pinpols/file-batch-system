package com.example.batch.console.domain.query;

import java.time.Instant;
import com.example.batch.common.model.PageRequest;

public record FileRecordQuery(
        String tenantId,
        String bizType,
        String fileStatus,
        Long fileId,
        String fileName,
        String traceId,
        Instant fromTime,
        Instant toTime,
        PageRequest pageRequest
) {
}
