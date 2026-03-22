package com.example.batch.orchestrator.domain.command;

import java.time.LocalDate;

public record CompensationSubmitCommand(
        String tenantId,
        String compensationType,
        Long targetId,
        String targetInstanceNo,
        String jobCode,
        LocalDate bizDate,
        String batchNo,
        Long relatedFileId,
        String channelCode,
        String reason,
        String operatorId,
        String approvalId,
        String strategy,
        String traceId
) {
}
