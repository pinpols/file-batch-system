package io.github.pinpols.batch.console.domain.job.web.query;

import io.github.pinpols.batch.console.web.query.PageQueryRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class JobPartitionQueryRequest extends PageQueryRequest {

  private String tenantId;
  private Long jobInstanceId;
  private String partitionStatus;
}
