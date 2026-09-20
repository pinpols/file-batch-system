package io.github.pinpols.batch.console.domain.ops.application.contract.response;

public record ConsoleBatchApprovalResultResponse(
    String approvalNo, boolean success, String message) {}
