package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.util.List;

public interface TriggerDefinitionMapper {

  List<TriggerDescriptor> selectAllScheduledDefinitions();

  List<TriggerDescriptor> selectDefinitionsWithPendingMisfires();

  TriggerDescriptor selectByWorkflowCode(String tenantId, String workflowCode);

  TriggerDescriptor selectByWorkflowDefinitionId(String tenantId, Long workflowDefinitionId);

  int updateWorkflowScheduleEnabled(String tenantId, String workflowCode, boolean enabled);
}
