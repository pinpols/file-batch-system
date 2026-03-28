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
    private String queueCode;
    private String workerGroup;
    private String scheduleType;
    private String scheduleExpr;
    private String calendarCode;
    private String windowCode;
    private String retryPolicy;
    private Integer retryMaxCount;
    private Integer timeoutSeconds;
    private String shardStrategy;
    private String executionHandler;
    private String paramSchema;
    private String defaultParams;
    private String description;
    private Boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
}
