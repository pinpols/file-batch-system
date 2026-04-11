package com.example.batch.console.domain.entity;

import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

@Data
public class PendingCatchUpEntity {

    private Long id;
    private String tenantId;
    private String requestId;
    private String jobCode;
    private LocalDate bizDate;
    private String requestStatus;
    private String traceId;
    private Instant createdAt;
    private Instant updatedAt;
}
