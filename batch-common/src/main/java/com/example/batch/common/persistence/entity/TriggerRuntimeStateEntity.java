package com.example.batch.common.persistence.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Data;

/**
 * 时间轮 trigger 运行时状态(对应 batch.trigger_runtime_state)。
 *
 * <p>替换 Quartz QRTZ_TRIGGERS 的 NEXT_FIRE_TIME / TRIGGER_STATE,作为时间轮调度器的权威源。 详见
 * docs/architecture/quartz-replacement-design.md §2。
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
   * FIRED / FAILED / SKIPPED_DUPLICATE / MISFIRE_CATCH_UP / MISFIRE_SKIPPED / MISFIRE_PENDING。 详见
   * V67 migration check 约束。
   */
  private String lastFireStatus;

  /**
   * 调度占位 marker:某 leader 把这一条推进 wheel 时写自己的 instance_id; fire 完毕清回 NULL。其他 leader / 同 leader
   * 下一周期扫库时跳过 marker IS NOT NULL 的行, 实现"滑动窗口去重"防 design.md §4 风险 R-2。
   */
  private String scheduledFireMarker;

  /** marker 写入时刻;周期任务用 (now() - scheduledAt > 5min) 接管 stale marker。 */
  private Instant scheduledAt;

  private Long misfireCount;

  /** 乐观锁;UPDATE 时 WHERE id=? AND version=?。 */
  private Integer version;

  /** cron 解释 next_fire_time 时使用的 IANA ZoneId 快照(V104)。 */
  private String scheduleTimezone;

  /** next_fire_time 在 scheduleTimezone 下的 LocalDate(V104,DST 排障审计)。 */
  private LocalDate scheduledLocalDate;

  /** next_fire_time 在 scheduleTimezone 下的 LocalTime(V104,DST 排障审计)。 */
  private LocalTime scheduledLocalTime;

  /**
   * 同一本地计划时间的连续 fire 计数(V104)。 DST overlap 第二次触发为 2,正常为 1。 提供给日批 / 高频 fire identity
   * 区分;fire_sequence 升 1 触发于 advanceAfterFire 检测到本地时间未变。
   */
  private Integer fireSequence;

  /**
   * ADR-043:依赖未就绪首次 defer 的原始 scheduled fire 时刻。非空 = 正在等上游就绪。
   *
   * <p>用于 (a) 算已等待时长是否超 readinessWindow;(b) 把 bizDate pin 到原始触发时刻——defer 期间 next_fire_time
   * 被改成"重检时钟"(now+recheckInterval),command 的 fireTime 仍用本字段防 bizDate 漂移到下一业务日。就绪 fire / 超窗 give-up
   * 后清回 NULL。
   */
  private Instant readinessDeferredSince;

  private Instant createdAt;
  private Instant updatedAt;
}
