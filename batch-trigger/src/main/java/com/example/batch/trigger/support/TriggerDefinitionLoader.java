package com.example.batch.trigger.support;

import java.util.List;

public interface TriggerDefinitionLoader {

    List<TriggerDescriptor> loadAll();

    TriggerDescriptor loadByJobCode(String tenantId, String jobCode);
}
