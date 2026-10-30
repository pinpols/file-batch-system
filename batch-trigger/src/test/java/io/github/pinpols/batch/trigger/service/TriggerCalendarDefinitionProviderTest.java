package io.github.pinpols.batch.trigger.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.trigger.mapper.BusinessCalendarMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("触发日历定义的权威来源")
class TriggerCalendarDefinitionProviderTest {

  @Test
  @DisplayName("显式配置的日历不存在或停用时定时触发失败关闭")
  void shouldFailClosedWhenConfiguredCalendarIsUnavailable() {
    BusinessCalendarMapper mapper = mock(BusinessCalendarMapper.class);
    when(mapper.selectActiveByTenantAndCalendarCode("tenant-a", "default_calendar"))
        .thenReturn(null);
    TriggerCalendarDefinitionProvider provider = new TriggerCalendarDefinitionProvider(mapper);

    assertThatThrownBy(() -> provider.load("tenant-a", "default-calendar"))
        .isInstanceOf(BizException.class);

    verify(mapper).selectActiveByTenantAndCalendarCode("tenant-a", "default_calendar");
  }
}
