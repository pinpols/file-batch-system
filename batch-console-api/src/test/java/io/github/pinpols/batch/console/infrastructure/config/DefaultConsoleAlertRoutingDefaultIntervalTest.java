package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.application.contract.request.config.AlertRoutingSaveRequest;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertRoutingConfigMapper;
import io.github.pinpols.batch.console.domain.notification.param.AlertRoutingConfigUpsertParam;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("告警路由创建: 未填写分组与重复间隔时使用默认秒数")
@ExtendWith(MockitoExtension.class)
class DefaultConsoleAlertRoutingDefaultIntervalTest {

  @Mock
  private AlertRoutingConfigMapper mapper;

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @Mock
  private ConsoleRequestMetadataResolver metadataResolver;

  @Test
  @DisplayName("group_interval 默认 300 秒、repeat_interval 默认 3600 秒、group_wait 默认 0 秒")
  void shouldApplyDefaultIntervals_whenRequestOmitsThem() {
    when(tenantGuard.resolveTenant("ta")).thenReturn("ta");
    when(metadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("req", "trace", "ta", "operator", null, null));
    AlertRoutingSaveRequest request = new AlertRoutingSaveRequest();
    request.setTenantId("ta");
    request.setRouteCode("PAYMENT");
    DefaultConsoleAlertRoutingApplicationService service =
        new DefaultConsoleAlertRoutingApplicationService(mapper, tenantGuard, metadataResolver);

    service.create(request);

    ArgumentCaptor<AlertRoutingConfigUpsertParam> captor =
        ArgumentCaptor.forClass(AlertRoutingConfigUpsertParam.class);
    verify(mapper).upsertAlertRoutingConfig(captor.capture());
    AlertRoutingConfigUpsertParam param = captor.getValue();
    assertThat(param.getGroupWaitSeconds()).isZero();
    assertThat(param.getGroupIntervalSeconds()).isEqualTo(300);
    assertThat(param.getRepeatIntervalSeconds()).isEqualTo(3600);
    assertThat(param.getCreatedBy()).isEqualTo("operator");
  }
}
