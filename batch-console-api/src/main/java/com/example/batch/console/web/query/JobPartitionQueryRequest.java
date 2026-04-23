package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class JobPartitionQueryRequest extends PageQueryRequest {

  private String tenantId;
  private Long jobInstanceId;
  private String partitionStatus;
}
