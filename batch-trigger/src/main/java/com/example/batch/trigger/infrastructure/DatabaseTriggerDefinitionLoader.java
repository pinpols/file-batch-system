package com.example.batch.trigger.infrastructure;

import com.example.batch.trigger.domain.TriggerDefinitionLoader;
import com.example.batch.trigger.mapper.TriggerDefinitionMapper;
import com.example.batch.trigger.support.TriggerDescriptor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 基于数据库的触发器定义加载器，通过 MyBatis Mapper 从持久层读取调度定义。 {@code loadAll} 仅返回已启用的调度型任务定义，用于启动时全量注册； {@code
 * loadByJobCode} 支持按租户和任务代码精确查询，用于动态注册单个触发器。
 */
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
