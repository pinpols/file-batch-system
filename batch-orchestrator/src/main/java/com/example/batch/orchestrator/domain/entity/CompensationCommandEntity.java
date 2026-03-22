package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

@Data
public class CompensationCommandEntity {

    private Long id;
    private String tenantId;
    private String commandNo;
    private String compensationType;
    private Long targetId;
    private String jobCode;
    private LocalDate bizDate;
    private String batchNo;
    private Long relatedJobInstanceId;
    private Long relatedFileId;
    private String approvalId;
    private String operatorId;
    private String reason;
    private String strategy;
    private String commandStatus;
    private String traceId;
    private String resultSummary;
    private String errorCode;
    private String errorMessage;
    private Instant createdAt;
    private Instant finishedAt;
}
