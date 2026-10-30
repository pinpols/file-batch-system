package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.common.enums.ScheduledOccurrenceStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 同一租户、Workflow 及调度版本的一批待计划触发时间和业务日期。 */
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
