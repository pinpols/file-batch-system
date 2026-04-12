package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class JobStepInstanceQueryRequest extends PageQueryRequest {

  private String tenantId;
  private Long jobInstanceId;
  private Long jobPartitionId;
  private String stepCode;
  private String stepStatus;
}
