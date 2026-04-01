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
    private String bizType;
    private String queueCode;
    private String workerGroup;
    private String scheduleType;
    private String scheduleExpr;
    private String timezone;
    private String calendarCode;
    private String windowCode;
    private String triggerMode;
    private Boolean dagEnabled;
    private String retryPolicy;
    private Integer retryMaxCount;
    private Integer timeoutSeconds;
    private String shardStrategy;
    private String executionHandler;
    private String paramSchema;
    private String defaultParams;
    private Integer priority;
    private Long version;
    private String description;
    private Boolean enabled;
    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
}
