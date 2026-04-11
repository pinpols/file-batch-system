package com.example.batch.trigger.domain;

import com.example.batch.trigger.support.TriggerDescriptor;

import java.util.List;

public interface TriggerDefinitionLoader {

    List<TriggerDescriptor> loadAll();

    TriggerDescriptor loadByJobCode(String tenantId, String jobCode);
}
