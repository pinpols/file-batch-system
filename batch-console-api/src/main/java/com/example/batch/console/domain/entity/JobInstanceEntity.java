package com.example.batch.console.domain.entity;

import lombok.Data;
import java.time.Instant;
import java.time.LocalDate;

@Data
public class JobInstanceEntity {

    private Long id;
    private String tenantId;
    private String jobCode;
    private String instanceNo;
    private LocalDate bizDate;
    private String triggerType;
    private String instanceStatus;
    private String batchNo;
    private String operatorId;
    private Boolean rerunFlag;
    private Boolean retryFlag;
    private String rerunReason;
    private Long relatedFileId;
    private Long parentInstanceId;
    private String queueCode;
    private String workerGroup;
    private Integer priority;
    private String traceId;
    private String paramsSnapshot;
    private String resultSummary;
    private Instant deadlineAt;
    private Integer expectedDurationSeconds;
    private Instant slaAlertedAt;
    private Instant startedAt;
    private Instant finishedAt;
}
