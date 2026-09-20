package io.github.pinpols.batch.console.domain.job.application.contract.response;

public record ConsoleBatchDayCatchUpItemResponse(
    String jobCode,
    String actionType,
    String referenceNo,
    String triggerType,
    String requestStatus) {}
