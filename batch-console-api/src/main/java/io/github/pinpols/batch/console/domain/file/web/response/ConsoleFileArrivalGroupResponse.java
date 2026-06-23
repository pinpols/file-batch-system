package io.github.pinpols.batch.console.domain.file.web.response;

import java.time.Instant;

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
