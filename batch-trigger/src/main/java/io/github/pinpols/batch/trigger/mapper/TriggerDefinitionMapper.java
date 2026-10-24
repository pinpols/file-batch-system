package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.util.List;

public interface TriggerDefinitionMapper {

  List<TriggerDescriptor> selectAllScheduledDefinitions();

  TriggerDescriptor selectByJobCode(String tenantId, String jobCode);

  TriggerDescriptor selectByWorkflowCode(String tenantId, String workflowCode);

  int updateScheduleEnabled(String tenantId, String jobCode, boolean enabled);

  int updateWorkflowScheduleEnabled(String tenantId, String workflowCode, boolean enabled);
}
