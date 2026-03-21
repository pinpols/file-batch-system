package com.example.batch.console.domain.entity;

import lombok.Data;
import java.time.Instant;

@Data
public class WorkerRegistryEntity {

    private Long id;
    private String tenantId;
    private String workerCode;
    private String workerGroup;
    private String status;
    private Instant heartbeatAt;
}
