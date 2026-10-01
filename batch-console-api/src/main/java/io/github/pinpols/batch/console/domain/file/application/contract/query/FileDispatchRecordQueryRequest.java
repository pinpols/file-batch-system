package io.github.pinpols.batch.console.domain.file.application.contract.query;

import io.github.pinpols.batch.console.application.contract.query.PageQueryRequest;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class FileDispatchRecordQueryRequest extends PageQueryRequest {

  private String tenantId;

  @Size(max = 128)
  private String keyword;

  private Long fileId;
  private String channelCode;
  private String dispatchStatus;
  private String receiptStatus;
  private String fromTime;
  private String toTime;
}
