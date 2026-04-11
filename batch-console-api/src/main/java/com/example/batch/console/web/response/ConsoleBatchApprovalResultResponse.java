package com.example.batch.console.web.response;

public record ConsoleBatchApprovalResultResponse(
        String approvalNo, boolean success, String message) {}
