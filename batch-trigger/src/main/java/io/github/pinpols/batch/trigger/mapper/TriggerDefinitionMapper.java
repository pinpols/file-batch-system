package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.util.List;

public interface TriggerDefinitionMapper {

  List<TriggerDescriptor> selectAllScheduledDefinitions();

  List<TriggerDescriptor> selectDefinitionsWithPendingMisfires();

  /** 指定租户下已配置周期调度但调度开关关闭（未注册、注销或暂停）的最新版本 Workflow。 */
  List<TriggerDescriptor> selectUnscheduledDefinitionsByTenant(String tenantId);

  /** 全部租户下已配置周期调度但调度开关关闭的最新版本 Workflow（平台管理员全局列表）。 */
  List<TriggerDescriptor> selectAllUnscheduledDefinitions();

  TriggerDescriptor selectByWorkflowCode(String tenantId, String workflowCode);

  TriggerDescriptor selectByWorkflowDefinitionId(String tenantId, Long workflowDefinitionId);

  int updateWorkflowScheduleEnabled(String tenantId, String workflowCode, boolean enabled);
}
