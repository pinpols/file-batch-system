package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.util.List;

public interface TriggerDefinitionMapper {

  List<TriggerDescriptor> selectAllScheduledDefinitions();

  TriggerDescriptor selectByJobCode(String tenantId, String jobCode);
}
