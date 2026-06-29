package io.github.pinpols.batch.orchestrator.domain.param;

import java.util.List;

public record QueueBacklogQueryParam(
    String tenantId,
    List<String> queueCodes,
    String createdStatus,
    String waitingStatus,
    String readyStatus,
    String runningStatus,
    String retryingStatus) {}
