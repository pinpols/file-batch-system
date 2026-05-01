package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;
import java.time.Instant;
import lombok.Builder;

@Builder
public record ConsoleAiAuditLogQuery(
    String tenantId,
    String sessionId,
    String operatorId,
    String promptCategory,
    String promptDecision,
    Instant fromTime,
    Instant toTime,
    PageRequest pageRequest) {

  public static ConsoleAiAuditLogQuery ofTenant(String tenantId, PageRequest pageRequest) {
    return builder().tenantId(tenantId).pageRequest(pageRequest).build();
  }
}
