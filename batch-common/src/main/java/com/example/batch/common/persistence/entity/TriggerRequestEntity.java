package com.example.batch.common.persistence.entity;

import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

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
