package com.example.batch.orchestrator.domain.entity;

import com.example.batch.orchestrator.domain.statemachine.Stateful;
import java.time.Instant;
import lombok.Data;

@Data
public class JobStepInstanceEntity implements Stateful {

    private Long id;
    private String tenantId;
    private Long jobInstanceId;
    private Long jobPartitionId;
    private Long jobTaskId;
    private String stepCode;
    private String stepType;
    private String stepStatus;
    /**
     * 步骤生命周期内的业务重试次数。
     */
    private Integer retryCount;
    private Long relatedFileId;
    private String resultSummary;
    private String errorCode;
    private String errorMessage;
    private Long version;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;
    private Instant updatedAt;

    @Override
    public String getStatus() {
        return stepStatus;
    }
}
