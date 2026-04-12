package com.example.batch.console.web.response;

import java.time.Instant;
import java.time.LocalDate;

public record ConsoleJobInstanceResponse(
    Long id,
    String tenantId,
    String jobCode,
    String instanceNo,
    LocalDate bizDate,
    String triggerType,
    String instanceStatus,
    String batchNo,
    String operatorId,
    Boolean rerunFlag,
    Boolean retryFlag,
    String rerunReason,
    Long relatedFileId,
    Long parentInstanceId,
    String queueCode,
    String workerGroup,
    Integer priority,
    String traceId,
    String paramsSnapshot,
    String resultSummary,
    Instant deadlineAt,
    Integer expectedDurationSeconds,
    Instant slaAlertedAt,
    Instant startedAt,
    Instant finishedAt) {}
