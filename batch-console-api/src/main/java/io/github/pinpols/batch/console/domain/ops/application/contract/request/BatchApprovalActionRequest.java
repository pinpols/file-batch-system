package io.github.pinpols.batch.console.domain.ops.application.contract.request;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

public record BatchApprovalActionRequest(
        String tenantId, @NotEmpty List<String> approvalNos, String operatorId, String reason) {}
