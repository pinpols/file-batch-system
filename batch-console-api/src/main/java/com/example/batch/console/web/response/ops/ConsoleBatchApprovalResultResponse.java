package com.example.batch.console.web.response.ops;

public record ConsoleBatchApprovalResultResponse(
    String approvalNo, boolean success, String message) {}
