package com.example.batch.trigger.infrastructure;

import com.example.batch.trigger.domain.TriggerDefinitionLoader;
import com.example.batch.trigger.mapper.TriggerDefinitionMapper;
import com.example.batch.trigger.support.TriggerDescriptor;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DatabaseTriggerDefinitionLoader implements TriggerDefinitionLoader {

    private final TriggerDefinitionMapper triggerDefinitionMapper;

    @Override
    public List<TriggerDescriptor> loadAll() {
        return triggerDefinitionMapper.selectAllScheduledDefinitions();
    }

    @Override
    public TriggerDescriptor loadByJobCode(String tenantId, String jobCode) {
        return triggerDefinitionMapper.selectByJobCode(tenantId, jobCode);
    }
}
