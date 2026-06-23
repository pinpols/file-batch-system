package io.github.pinpols.batch.trigger.domain;

import io.github.pinpols.batch.trigger.mapper.TriggerDefinitionMapper;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 触发器定义加载器,通过 MyBatis Mapper 从持久层读取调度配置。
 *
 * <p>{@link #loadAll} 只返回处于启用状态的调度定义,用于启动时全量注册或对账。 {@link #loadByJobCode} 在找不到对应记录时返回 {@code
 * null},由调用方决定是否抛出业务异常。
 *
 * <p>R-arch-audit-2026-05-23 P1: 历史上本类是接口 + DatabaseTriggerDefinitionLoader 单实现的形式主义, wheel 与
 * quartz 路径共用同一实现,接口的 Mock 价值很低 (MyBatis Mapper 本身可 mock)。 评估后删除接口,将实现并入本类,消除多余的间接层。
 */
@Component
@RequiredArgsConstructor
public class TriggerDefinitionLoader {

  private final TriggerDefinitionMapper triggerDefinitionMapper;

  public List<TriggerDescriptor> loadAll() {
    return triggerDefinitionMapper.selectAllScheduledDefinitions();
  }

  public TriggerDescriptor loadByJobCode(String tenantId, String jobCode) {
    return triggerDefinitionMapper.selectByJobCode(tenantId, jobCode);
  }
}
