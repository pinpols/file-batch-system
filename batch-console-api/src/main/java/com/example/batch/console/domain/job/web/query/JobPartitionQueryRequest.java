package com.example.batch.console.domain.job.web.query;

import com.example.batch.console.web.query.PageQueryRequest;
import lombok.Data;

@Data
public class JobPartitionQueryRequest extends PageQueryRequest {

  private String tenantId;
  private Long jobInstanceId;
  private String partitionStatus;
}
