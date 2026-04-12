package com.example.batch.console.web.query;

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
