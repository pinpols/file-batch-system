package com.example.batch.console.web.request;

import lombok.Data;

@Data
public class CatchUpApprovalRequest {

    private String tenantId;
    private String requestId;
    private String jobCode;
    private String bizDate;
    private String scheduledAt;
    private String reason;
}
