package com.example.batch.trigger.domain;

import com.example.batch.trigger.support.TriggerDescriptor;
import java.util.List;

/**
 * 触发器定义加载接口，定义从持久层读取调度配置的契约。
 * 实现类须保证 {@code loadAll} 只返回处于启用状态的调度定义；
 * {@code loadByJobCode} 在找不到对应记录时应返回 {@code null} 而非抛出异常，
 * 由调用方决定是否抛出业务异常。
 */
public interface TriggerDefinitionLoader {

  List<TriggerDescriptor> loadAll();

  TriggerDescriptor loadByJobCode(String tenantId, String jobCode);
}
