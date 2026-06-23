package io.github.pinpols.batch.console.domain.job.web.response;

public record ConsoleBatchDayCatchUpItemResponse(
    String jobCode,
    String actionType,
    String referenceNo,
    String triggerType,
    String requestStatus) {}
