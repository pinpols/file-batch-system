package com.example.batch.console.web.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BatchApprovalActionRequest(
        String tenantId,
        @NotEmpty List<String> approvalNos,
        String operatorId,
        String reason) {}
