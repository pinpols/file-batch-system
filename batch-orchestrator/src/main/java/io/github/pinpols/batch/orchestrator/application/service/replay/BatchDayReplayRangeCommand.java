package io.github.pinpols.batch.orchestrator.application.service.replay;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 闭区间业务日期的重放请求。 */
public record BatchDayReplayRangeCommand(
    String tenantId,
    String calendarCode,
    LocalDate fromDate,
    LocalDate throughDate,
    String scope,
    List<String> jobCodes,
    List<Long> versionIds,
    String resultPolicy,
    String configVersionPolicy,
    Integer configVersion,
    String reason,
    String requestedBy,
    boolean autoApprove,
    String traceId,
    String executionMode,
    String candidateSource,
    UUID operationKey,
    String previewToken) {}
