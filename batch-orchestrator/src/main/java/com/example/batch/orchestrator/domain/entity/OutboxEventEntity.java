package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class OutboxEventEntity {

  private Long id;
  private String tenantId;
  private String aggregateType;
  private Long aggregateId;
  private String eventType;
  private String eventKey;
  private String payloadJson;
  private String publishStatus;

  /**
   * outbox 投递尝试序号。
   *
   * <p>它与运行时实体上保存的业务重试计数是不同的概念。
   */
  private Integer publishAttempt;

  private Instant nextPublishAt;
  private String traceId;

  /** V88: 拷自 source job_definition.priority. OutboxPollScheduler 按 desc 排序优先派发. 默认 5 (范围 0-10). */
  private Integer priority;

  private Instant createdAt;
  private Instant updatedAt;
}
