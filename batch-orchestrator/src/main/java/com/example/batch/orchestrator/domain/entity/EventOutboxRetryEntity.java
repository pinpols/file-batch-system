package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class EventOutboxRetryEntity {

  private Long id;
  private String tenantId;
  private Long outboxEventId;
  private String eventKey;

  /**
   * outbox 事件的投递尝试序号。
   *
   * <p>它与作业/运行时实体上的业务重试计数是两套不同概念。
   */
  private Integer publishAttempt;

  private String retryStatus;
  private String retryReason;
  private Instant nextRetryAt;
  private String traceId;
  private Instant createdAt;
  private Instant updatedAt;
}
