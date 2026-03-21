package com.example.batch.console.domain.entity;

import lombok.Data;
import java.time.Instant;

@Data
public class JobDefinitionEntity {

    private Long id;
    private String tenantId;
    private String jobCode;
    private String jobName;
    private String jobType;
    private String scheduleType;
    private String scheduleExpr;
    private String workerGroup;
    private Boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
}
