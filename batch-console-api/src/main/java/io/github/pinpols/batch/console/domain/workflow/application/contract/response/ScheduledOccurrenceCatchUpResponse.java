package io.github.pinpols.batch.console.domain.workflow.application.contract.response;

public record ScheduledOccurrenceCatchUpResponse(
    String requestId, String status, String approvalId) {}
