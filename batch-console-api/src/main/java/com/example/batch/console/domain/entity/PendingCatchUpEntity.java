package com.example.batch.console.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

@Data
public class PendingCatchUpEntity {

  private Long id;
  private String tenantId;
  private String requestId;
  private String jobCode;
  private LocalDate bizDate;
  private String requestStatus;
  private String traceId;
  private Instant createdAt;
  private Instant updatedAt;
}
