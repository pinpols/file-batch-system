package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

@Data
public class TriggerRequestEntity {

    private Long id;
    private String tenantId;
    private String requestId;
    private String triggerType;
    private String jobCode;
    private LocalDate bizDate;
    private String dedupKey;
    private String requestStatus;
    private Long relatedJobInstanceId;
    private String traceId;
    private Instant createdAt;
    private Instant updatedAt;
}
