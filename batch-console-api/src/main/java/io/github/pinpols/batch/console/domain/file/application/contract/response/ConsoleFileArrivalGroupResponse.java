package io.github.pinpols.batch.console.domain.file.application.contract.response;

import java.time.Instant;
import lombok.Builder;

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
