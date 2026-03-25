package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class ApprovalCommandQuery {

    private String tenantId;
    private String approvalNo;
    private String approvalType;
    private String actionType;
    private String approvalStatus;
    private Integer limit;
}

