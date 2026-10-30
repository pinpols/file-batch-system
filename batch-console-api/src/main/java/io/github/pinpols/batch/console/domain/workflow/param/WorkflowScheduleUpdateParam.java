package io.github.pinpols.batch.console.domain.workflow.param;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import lombok.Builder;

/** 日程更新的固定 SQL 入参；只更新日程字段并比较原版本，不改定义启停和受管 Job 关系。 */
@Builder(toBuilder = true)
public record WorkflowScheduleUpdateParam(
    String tenantId,
    Long id,
    Integer expectedVersion,
    String scheduleType,
    String scheduleExpr,
    String timezone,
    String triggerMode,
    String calendarCode,
    String dependsOnJobCode) {

  public WorkflowScheduleUpdateParam {
    boolean scheduleUpdateIdentityIsIncomplete = EmptyChecks.isBlank(tenantId)
        || EmptyChecks.isNull(id)
        || EmptyChecks.isNull(expectedVersion)
        || EmptyChecks.isBlank(scheduleType);
    if (scheduleUpdateIdentityIsIncomplete) {
      throw new IllegalArgumentException(
          "tenantId, id, expectedVersion and scheduleType are required for Workflow schedules");
    }
  }
}
