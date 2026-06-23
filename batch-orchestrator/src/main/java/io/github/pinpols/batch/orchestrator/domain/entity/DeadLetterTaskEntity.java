package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class DeadLetterTaskEntity {

  private Long id;
  private String tenantId;
  private String sourceType;
  private Long sourceId;
  private String deadLetterReason;
  private String payloadRef;
  private String replayStatus;
  private Integer replayCount;
  private Instant lastReplayAt;
  private String lastReplayResult;
  private String traceId;

  /** V90: 自动重放上限. 超过后 scheduler 转 GIVE_UP, 人工仍可触发. */
  private Integer maxReplayCount;

  /** V90: 下一次自动重放时间. NULL 表示不安排自动重放. */
  private Instant nextReplayAt;

  /** V90: BUSINESS 不参与自动重放, SYSTEM 走指数退避自动重放. */
  private String errorClass;

  private Instant createdAt;
  private Instant updatedAt;
}
