package io.github.pinpols.batch.console.domain.job.application.contract.query;

import io.github.pinpols.batch.console.application.contract.query.PageQueryRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class JobPartitionQueryRequest extends PageQueryRequest {

  private String tenantId;
  private Long jobInstanceId;
  private String partitionStatus;
}
