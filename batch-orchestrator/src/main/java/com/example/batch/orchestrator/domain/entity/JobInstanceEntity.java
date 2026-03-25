package com.example.batch.orchestrator.domain.entity;

import com.example.batch.orchestrator.domain.statemachine.Stateful;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

@Data
public class JobInstanceEntity implements Stateful {

    private Long id;
    private String tenantId;
    private Long jobDefinitionId;
    private Long triggerRequestId;
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
    private String dedupKey;
    private Long version;
    private Integer expectedPartitionCount;
    private Integer successPartitionCount;
    private Integer failedPartitionCount;
    private String traceId;
    private String paramsSnapshot;
    private String resultSummary;
    private Instant deadlineAt;
    private Integer expectedDurationSeconds;
    private Instant slaAlertedAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;
    private Instant updatedAt;

    @Override
    public String getStatus() {
        return instanceStatus;
    }
}
