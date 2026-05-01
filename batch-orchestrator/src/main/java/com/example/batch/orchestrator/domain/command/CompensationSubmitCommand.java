package com.example.batch.orchestrator.domain.command;

import java.time.LocalDate;
import lombok.Builder;

@Builder
public record CompensationSubmitCommand(
    String tenantId,
    String compensationType,
    Long targetId,
    String targetInstanceNo,
    /** 补偿范围对应的业务 Job 标识。 */
    String jobCode,
    LocalDate bizDate,
    String batchNo,
    Long relatedFileId,
    String channelCode,
    String reason,
    String operatorId,
    String approvalId,
    String strategy,
    String traceId) {}
