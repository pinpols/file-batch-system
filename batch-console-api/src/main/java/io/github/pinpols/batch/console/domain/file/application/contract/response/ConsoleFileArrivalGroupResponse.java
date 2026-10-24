package io.github.pinpols.batch.console.domain.file.application.contract.response;

import lombok.Builder;

import java.time.Instant;
@Builder
public record ConsoleFileArrivalGroupResponse(
    String tenantId,
    String fileGroupCode,
    String waitFileGroupMode,
    String requiredFileSet,
    String arrivalTimeoutAction,
    String arrivalState,
    Instant expectedArrivalTime,
    Instant latestTolerableTime,
    Long arrivedCount,
    Long triggeredCount,
    Long timeoutCount,
    Long waitingCount,
    Instant lastUpdatedAt) {}
