package com.example.batch.console.domain.audit.web.query;

import com.example.batch.console.web.query.PageQueryRequest;
import lombok.Data;

@Data
public class ConsoleAiAuditLogQueryRequest extends PageQueryRequest {

  private String tenantId;
  private String sessionId;
  private String operatorId;
  private String promptCategory;
  private String promptDecision;
  private String fromTime;
  private String toTime;
}
