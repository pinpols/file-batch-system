package io.github.pinpols.batch.console.domain.workflow.param;

import lombok.Builder;

import io.github.pinpols.batch.common.utils.EmptyValues;

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
        boolean scheduleUpdateIdentityIsIncomplete = EmptyValues.isBlank(tenantId)
                || id == null
                || expectedVersion == null
                || EmptyValues.isBlank(scheduleType);
        if (scheduleUpdateIdentityIsIncomplete) {
            throw new IllegalArgumentException(
                    "tenantId, id, expectedVersion and scheduleType are required for Workflow schedules");
        }
    }
}
