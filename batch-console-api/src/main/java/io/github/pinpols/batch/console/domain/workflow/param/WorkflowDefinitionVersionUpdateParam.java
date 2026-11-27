package io.github.pinpols.batch.console.domain.workflow.param;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import lombok.Builder;

/**
 * 带版本比较的全量更新；缺版本必须在构造边界拒绝，不能移除 SQL 的 CAS 条件。
 *
 * <p>{@code scheduleEnabled} 为空表示本次更新不改动定时开关：该列由显式启停入口维护，只有配置包显式提供时才随定义写入。
 */
@Builder(toBuilder = true)
public record WorkflowDefinitionVersionUpdateParam(
        String tenantId,
        Long id,
        Integer expectedVersion,
        String workflowName,
        String workflowType,
        Boolean enabled,
        Boolean scheduleEnabled,
        String scheduleType,
        String scheduleExpr,
        String timezone,
        String triggerMode,
        String calendarCode,
        String dependsOnJobCode,
        String description) {

    public WorkflowDefinitionVersionUpdateParam {
        if (EmptyChecks.isBlank(tenantId) || id == null || expectedVersion == null) {
            throw new IllegalArgumentException(
                    "tenantId, id and expectedVersion are required for Workflow replacement");
        }
    }
}
