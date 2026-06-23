package io.github.pinpols.batch.console.domain.ops.web.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record BatchApprovalActionRequest(
    String tenantId, @NotEmpty List<String> approvalNos, String operatorId, String reason) {}
