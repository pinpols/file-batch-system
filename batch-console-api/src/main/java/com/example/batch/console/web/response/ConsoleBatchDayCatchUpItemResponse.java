package com.example.batch.console.web.response;

public record ConsoleBatchDayCatchUpItemResponse(
    String jobCode,
    String actionType,
    String referenceNo,
    String triggerType,
    String requestStatus) {}
