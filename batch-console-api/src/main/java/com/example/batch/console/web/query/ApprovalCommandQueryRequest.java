package com.example.batch.console.web.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ApprovalCommandQueryRequest {

    @NotBlank
    private String tenantId;
    private String approvalNo;
    private String approvalType;
    private String actionType;
    private String approvalStatus;
    private Integer limit;
}

