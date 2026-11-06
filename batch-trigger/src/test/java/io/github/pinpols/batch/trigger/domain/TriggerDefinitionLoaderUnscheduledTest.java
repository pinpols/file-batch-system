package io.github.pinpols.batch.trigger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.trigger.mapper.TriggerDefinitionMapper;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("触发器定义加载：调度关闭的周期 Workflow 按租户范围查询")
class TriggerDefinitionLoaderUnscheduledTest {

  @Test
  @DisplayName("指定租户时只查该租户")
  void tenant_scope_uses_tenant_query() {
    TriggerDefinitionMapper mapper = mock(TriggerDefinitionMapper.class);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    when(mapper.selectUnscheduledDefinitionsByTenant("tenant-a")).thenReturn(List.of(descriptor));

    assertThat(new TriggerDefinitionLoader(mapper).loadUnscheduledDefinitions("tenant-a"))
        .containsExactly(descriptor);
    verify(mapper, never()).selectAllUnscheduledDefinitions();
  }

  @Test
  @DisplayName("未指定租户时与 Quartz 全局列表一致，查询全部租户")
  void missing_tenant_scope_uses_global_query() {
    TriggerDefinitionMapper mapper = mock(TriggerDefinitionMapper.class);
    when(mapper.selectAllUnscheduledDefinitions()).thenReturn(List.of());

    assertThat(new TriggerDefinitionLoader(mapper).loadUnscheduledDefinitions(null))
        .isEmpty();
    verify(mapper, never()).selectUnscheduledDefinitionsByTenant(null);
  }
}
