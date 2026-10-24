package io.github.pinpols.batch.console.domain.workflow.param;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import lombok.Builder;

/** 带版本比较的全量更新；缺版本必须在构造边界拒绝，不能移除 SQL 的 CAS 条件。 */
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
    String dependsOnJobCode) {

  public WorkflowDefinitionVersionUpdateParam {
    if (EmptyChecks.isBlank(tenantId) || EmptyChecks.isNull(id) || EmptyChecks.isNull(expectedVersion)) {
      throw new IllegalArgumentException("tenantId, id and expectedVersion are required for Workflow replacement");
    }
  }
}
