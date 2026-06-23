package io.github.pinpols.batch.console.domain.file.web.query;

import io.github.pinpols.batch.console.web.query.PageQueryRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class FileDispatchRecordQueryRequest extends PageQueryRequest {

  private String tenantId;
  private Long fileId;
  private String channelCode;
  private String dispatchStatus;
  private String receiptStatus;
  private String fromTime;
  private String toTime;
}
