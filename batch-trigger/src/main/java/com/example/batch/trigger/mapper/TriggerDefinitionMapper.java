package com.example.batch.trigger.mapper;

import com.example.batch.trigger.support.TriggerDescriptor;

import java.util.List;

public interface TriggerDefinitionMapper {

    List<TriggerDescriptor> selectAllScheduledDefinitions();

    TriggerDescriptor selectByJobCode(String tenantId, String jobCode);
}
