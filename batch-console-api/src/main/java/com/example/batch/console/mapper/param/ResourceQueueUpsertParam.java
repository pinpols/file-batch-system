package com.example.batch.console.mapper.param;

import lombok.Data;

@Data
public class ResourceQueueUpsertParam {

  private String tenantId;
  private String queueCode;
  private String queueName;
  private String queueType;
  private Integer maxRunningJobs;
  private Integer maxRunningPartitions;
  private Integer maxQps;
  private String workerGroup;
  private String resourceTag;
  private String priorityPolicy;
  private Integer fairShareWeight;
  private Boolean enabled;
  private String description;
  private String createdBy;
  private String updatedBy;
}
