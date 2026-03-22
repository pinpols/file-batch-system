package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class AuditLogQueryRequest {

    private String tenantId;
    private String operationType;
    private String traceId;
    private String fromTime;
    private String toTime;
}
