package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class ConsoleAiAuditLogQueryRequest {

    private String tenantId;
    private String sessionId;
    private String operatorId;
    private String promptCategory;
    private String promptDecision;
    private String fromTime;
    private String toTime;
}
