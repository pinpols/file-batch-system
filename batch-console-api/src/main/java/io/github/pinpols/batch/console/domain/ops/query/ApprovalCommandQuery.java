package io.github.pinpols.batch.console.domain.ops.query;

import java.util.List;

import lombok.Data;

import io.github.pinpols.batch.common.model.PageRequest;

@Data
public class ApprovalCommandQuery {

    private String tenantId;
    private String approvalNo;
    private String approvalType;
    private List<String> excludeApprovalTypes;
    private String actionType;
    private String approvalStatus;
    private String requesterId;
    private String keyword;
    private PageRequest pageRequest;
}
