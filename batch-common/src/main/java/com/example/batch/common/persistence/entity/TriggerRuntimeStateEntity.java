package com.example.batch.common.persistence.entity;

import java.time.Instant;
import lombok.Data;

/**
 * 时间轮 trigger 运行时状态(对应 batch.trigger_runtime_state)。
 *
 * <p>替换 Quartz QRTZ_TRIGGERS 的 NEXT_FIRE_TIME / TRIGGER_STATE,作为时间轮调度器的权威源。
 * 详见 docs/architecture/quartz-replacement-design.md §2。
 */
@Data
public class TriggerRuntimeStateEntity {

  private Long id;
  private Long jobDefinitionId;
  private String tenantId;
  private String jobCode;

  /** 下次 fire 时刻(Quartz CronExpression.getNextValidTimeAfter 计算)。 */
  private Instant nextFireTime;

  /** 上次实际 fire 时刻。 */
  private Instant lastFireTime;

  /**
   * FIRED / FAILED / SKIPPED_DUPLICATE / MISFIRE_CATCH_UP / MISFIRE_SKIPPED / MISFIRE_PENDING。
   * 详见 V67 migration check 约束。
   */
  private String lastFireStatus;

  /**
   * 调度占位 marker:某 leader 把这一条推进 wheel 时写自己的 instance_id;
   * fire 完毕清回 NULL。其他 leader / 同 leader 下一周期扫库时跳过 marker IS NOT NULL 的行,
   * 实现"滑动窗口去重"防 design.md §4 风险 R-2。
   */
  private String scheduledFireMarker;

  /** marker 写入时刻;周期任务用 (now() - scheduledAt > 5min) 接管 stale marker。 */
  private Instant scheduledAt;

  private Long misfireCount;

  /** 乐观锁;UPDATE 时 WHERE id=? AND version=?。 */
  private Integer version;

  private Instant createdAt;
  private Instant updatedAt;
}
