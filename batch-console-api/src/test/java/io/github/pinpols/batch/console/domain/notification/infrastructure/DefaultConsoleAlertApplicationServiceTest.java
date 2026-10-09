package io.github.pinpols.batch.console.domain.notification.infrastructure;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.console.application.contract.request.ops.AlertActionRequest;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEventMapper;
import io.github.pinpols.batch.console.domain.notification.service.AlertmanagerSilenceBridge;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Console 告警关闭与 Alertmanager 活动组解除")
class DefaultConsoleAlertApplicationServiceTest {

  private static final String TENANT_ID = "tenant-a";
  private static final long ALERT_ID = 41L;

  @Mock
  private TenantIdResolver tenantResolver;

  @Mock
  private AlertEventMapper alertEventMapper;

  @Mock
  private ConsoleRealtimeEventPort realtimeEventPort;

  @Mock
  private AlertmanagerSilenceBridge alertmanagerSilenceBridge;

  private DefaultConsoleAlertApplicationService service;

  @BeforeEach
  void setUp() {
    service = new DefaultConsoleAlertApplicationService(
        tenantResolver, alertEventMapper, realtimeEventPort, alertmanagerSilenceBridge);
    when(tenantResolver.resolveTenant(TENANT_ID)).thenReturn(TENANT_ID);
    when(alertEventMapper.selectById(TENANT_ID, ALERT_ID)).thenReturn(activeAlert());
    when(alertEventMapper.updateStatus(TENANT_ID, ALERT_ID, "CLOSED")).thenReturn(1);
  }

  @DisplayName("同组仍有 OPEN 告警时不发送整组解除")
  @Test
  void shouldKeepAmGroupOpenWhenAnotherMatchingAlertIsOpen() {
    when(alertEventMapper.countActiveAlertsInAmGroup(
            TENANT_ID, "batch-orchestrator", "JOB_RUNNING_TOO_LONG", "WARN", ALERT_ID))
        .thenReturn(1L);

    service.close(ALERT_ID, request(), "close-1");

    verify(alertmanagerSilenceBridge, never()).resolve(org.mockito.ArgumentMatchers.any());
  }

  @DisplayName("同组最后一条 OPEN 告警关闭后发送解除")
  @Test
  void shouldResolveAmGroupWhenNoMatchingAlertRemainsOpen() {
    AlertEventEntity alert = activeAlert();
    when(alertEventMapper.countActiveAlertsInAmGroup(
            TENANT_ID, "batch-orchestrator", "JOB_RUNNING_TOO_LONG", "WARN", ALERT_ID))
        .thenReturn(0L);

    service.close(ALERT_ID, request(), "close-2");

    verify(alertmanagerSilenceBridge).resolve(alert);
  }

  private static AlertActionRequest request() {
    AlertActionRequest request = new AlertActionRequest();
    request.setTenantId(TENANT_ID);
    request.setOperatorId("operator");
    request.setReason("incident recovered");
    return request;
  }

  private static AlertEventEntity activeAlert() {
    AlertEventEntity alert = new AlertEventEntity();
    alert.setId(ALERT_ID);
    alert.setTenantId(TENANT_ID);
    alert.setServiceName("batch-orchestrator");
    alert.setAlertType("JOB_RUNNING_TOO_LONG");
    alert.setSeverity("WARN");
    alert.setStatus("OPEN");
    return alert;
  }
}
