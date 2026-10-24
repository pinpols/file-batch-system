package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.common.enums.ScheduledOccurrenceStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 等待每日围栏期间，扫描候选项可能已经过期。只领取仍 */
public record ScheduledOccurrencePlanBatchParam(
    String tenantId,
    Long workflowDefinitionId,
    String workflowCode,
    int scheduleVersion,
    String calendarCode,
    List<Fire> fires) {

  public String targetStatus() {
    return ScheduledOccurrenceStatus.PLANNED.code();
  }

  public record Fire(Instant scheduledFireTime, LocalDate bizDate) {}
}
