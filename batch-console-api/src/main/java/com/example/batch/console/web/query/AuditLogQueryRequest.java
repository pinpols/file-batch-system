package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class AuditLogQueryRequest extends PageQueryRequest {

  private String tenantId;
  private String operationType;
  private String operationResult;
  private String operatorId;
  private String fileId;
  private String traceId;
  private String fromTime;
  private String toTime;
  private String startTime;
  private String endTime;
}
