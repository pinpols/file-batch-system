package com.example.batch.console.domain.file.web.query;

import com.example.batch.console.web.query.PageQueryRequest;
import lombok.Data;

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
