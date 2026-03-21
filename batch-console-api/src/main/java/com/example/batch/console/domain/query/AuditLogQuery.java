package com.example.batch.console.domain.query;

import java.time.Instant;
import lombok.Data;

@Data
public class AuditLogQuery {

    private String tenantId;
    private String operationType;
    private String traceId;
    private Instant fromTime;
    private Instant toTime;
}
