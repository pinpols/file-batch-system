package com.example.batch.trigger.support;

import com.example.batch.trigger.mapper.TriggerDefinitionMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DatabaseTriggerDefinitionLoader implements TriggerDefinitionLoader {

    private final TriggerDefinitionMapper triggerDefinitionMapper;

    @Override
    public List<TriggerDescriptor> loadAll() {
        return triggerDefinitionMapper.selectAllCronDefinitions();
    }

    @Override
    public TriggerDescriptor loadByJobCode(String tenantId, String jobCode) {
        return triggerDefinitionMapper.selectByJobCode(tenantId, jobCode);
    }
}
