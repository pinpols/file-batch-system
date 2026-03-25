package com.example.batch.orchestrator.domain.entity;

import com.example.batch.orchestrator.domain.statemachine.Stateful;
import java.time.Instant;
import lombok.Data;

@Data
public class JobPartitionEntity implements Stateful {

    private Long id;
    private String tenantId;
    private Long jobInstanceId;
    private Integer partitionNo;
    private String partitionKey;
    private String partitionStatus;
    private String workerGroup;
    private String workerCode;
    private Instant leaseExpireAt;
    private Integer retryCount;
    private String businessKey;
    private String idempotencyKey;
    private String inputSnapshot;
    private String outputSummary;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;
    private Instant updatedAt;

    @Override
    public String getStatus() {
        return partitionStatus;
    }
}
