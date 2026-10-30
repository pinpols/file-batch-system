package io.github.pinpols.batch.orchestrator.infrastructure.governance;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.config.AlertmanagerEmitProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("告警重发的租户故障隔离与启停")
class AlertmanagerReemitSchedulerTest {
  @Test
  @DisplayName("单租户失败仍处理后续租户")
  void shouldContinueAfterTenantFailure() {
    AlertmanagerEmitProperties properties = new AlertmanagerEmitProperties();
    properties.setEnabled(true);
    AlertmanagerEmitPublisher publisher = mock(AlertmanagerEmitPublisher.class);
    when(publisher.isEnabled()).thenReturn(true);
    AlertmanagerReemitService service = mock(AlertmanagerReemitService.class);
    doThrow(new IllegalStateException("injected tenant failure"))
        .when(service)
        .reemitTenant("tenant-a");
    TenantDirectoryMapper directory = mock(TenantDirectoryMapper.class);
    when(directory.selectAllTenantIds()).thenReturn(List.of("tenant-a", "tenant-b"));
    AlertmanagerReemitScheduler scheduler = new AlertmanagerReemitScheduler(
        properties, publisher, service, directory, mock(OrchestratorGracefulShutdown.class));

    scheduler.reemit();

    verify(service).reemitTenant("tenant-b");
  }

  @Test
  @DisplayName("关闭开关时不读取目录或发送")
  void shouldSkipWhenDisabled() {
    AlertmanagerEmitProperties properties = new AlertmanagerEmitProperties();
    AlertmanagerEmitPublisher publisher = mock(AlertmanagerEmitPublisher.class);
    AlertmanagerReemitService service = mock(AlertmanagerReemitService.class);
    TenantDirectoryMapper directory = mock(TenantDirectoryMapper.class);
    AlertmanagerReemitScheduler scheduler = new AlertmanagerReemitScheduler(
        properties, publisher, service, directory, mock(OrchestratorGracefulShutdown.class));

    scheduler.reemit();

    verifyNoInteractions(directory, service, publisher);
  }
}
