package com.example.batch.console.mapper.param;

import lombok.Data;

@Data
public class JobDefinitionMaintenanceUpdateParam {

  private String tenantId;
  private String jobCode;
  private String jobName;
  private String queueCode;
  private String workerGroup;
  private String scheduleExpr;
  private String calendarCode;
  private String windowCode;
  private String retryPolicy;
  private Integer retryMaxCount;
  private Integer timeoutSeconds;
  private String shardStrategy;
  private String executionMode;
  private String watermarkField;
  private Boolean enabled;
  private String description;
  private String updatedBy;
}
