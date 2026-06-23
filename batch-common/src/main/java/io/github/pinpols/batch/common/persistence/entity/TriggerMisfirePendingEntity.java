package io.github.pinpols.batch.common.persistence.entity;

import java.time.Instant;
import lombok.Data;

/**
 * MANUAL_APPROVAL 策略 misfire 待审表(对应 batch.trigger_misfire_pending)。
 *
 * <p>当 trigger 的 catch_up_policy=MANUAL_APPROVAL 且发生 misfire 时,时间轮不自动补 fire, 而是落 PENDING 行,等运维通过
 * console UI 审批。详见 docs/architecture/quartz-replacement-design.md §9.4。
 */
@Data
public class TriggerMisfirePendingEntity {

  private Long id;
  private Long triggerRuntimeStateId;
  private String tenantId;
  private String jobCode;

  /** 错过的预定 fire 时刻。 */
  private Instant scheduledFireTime;

  /** 实际发现 misfire 的时刻;detected_at - scheduled_fire_time = 延迟。 */
  private Instant detectedAt;

  /** PENDING / APPROVED / REJECTED / EXPIRED。 */
  private String status;

  private String approvedBy;
  private Instant approvedAt;
  private String rejectionReason;

  /** 审批通过后真正补 fire 的 trigger_request.id。 */
  private Long catchUpRequestId;

  /** 默认 detectedAt + 7 days;周期任务把 PENDING + 已过期 改 EXPIRED。 */
  private Instant expiresAt;

  private Instant createdAt;
  private Instant updatedAt;
}
