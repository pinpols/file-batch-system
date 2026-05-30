package com.example.batch.console.domain.ops.web.response;

public record ConsoleBatchApprovalResultResponse(
    String approvalNo, boolean success, String message) {}
