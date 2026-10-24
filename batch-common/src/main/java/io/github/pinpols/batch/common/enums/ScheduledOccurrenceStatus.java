package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** 持久化定时触发投影的生命周期状态，与 trigger_request 状态分开管理。 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ScheduledOccurrenceStatus implements DictEnum {
  PLANNED("PLANNED", "计划中"),
  REQUESTED("REQUESTED", "已请求"),
  WAITING_READINESS("WAITING_READINESS", "等待就绪"),
  MISFIRE_PENDING("MISFIRE_PENDING", "Misfire 待处理"),
  SKIPPED_CALENDAR("SKIPPED_CALENDAR", "日历跳过"),
  MISFIRE_SKIPPED("MISFIRE_SKIPPED", "Misfire 跳过"),
  READINESS_TIMEOUT("READINESS_TIMEOUT", "就绪超时"),
  CLOSED_DAY_REJECTED("CLOSED_DAY_REJECTED", "批量日已关闭，拒绝迟到触发"),
  SUPERSEDED("SUPERSEDED", "已被替代");

  private final String code;
  private final String label;
}
