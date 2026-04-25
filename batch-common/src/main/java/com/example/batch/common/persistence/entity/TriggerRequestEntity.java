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

  /**
   * 时间轮 fire 路径专用:预定 fire 时刻;非时间轮路径(API/MANUAL/EVENT)为 null。
   * 与 {@link #triggerRuntimeStateId} 配合走 partial UNIQUE INDEX uk_trigger_request_fire_dedup,
   * 防双 leader 重复 fire(详见 docs/architecture/quartz-replacement-design.md §3)。
   */
  private Instant scheduledFireTime;

  /** 时间轮 fire 路径专用:关联 trigger_runtime_state.id;非时间轮路径为 null。 */
  private Long triggerRuntimeStateId;
}
