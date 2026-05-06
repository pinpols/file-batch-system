package com.example.batch.common.persistence.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

@Data
public class TriggerRequestEntity {

  private Long id;
  private String tenantId;
  private String requestId;
  private String triggerType;
  private String jobCode;
  private LocalDate bizDate;
  private String dedupKey;
  private String requestStatus;
  private Long relatedJobInstanceId;
  private String traceId;
  private int forwardRetryCount;
  private Instant createdAt;
  private Instant updatedAt;

  /** ADR-026 dry-run 演练标记；trigger 入口染色，落到 LaunchRequest.dryRun 透传到主链路。 */
  private Boolean dryRun;
}
