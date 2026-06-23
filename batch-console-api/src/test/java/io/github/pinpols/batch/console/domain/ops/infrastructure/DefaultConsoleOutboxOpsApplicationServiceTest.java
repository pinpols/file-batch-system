package io.github.pinpols.batch.console.domain.ops.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEventPublisher;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleOrchestratorProxyService;
import io.github.pinpols.batch.console.domain.ops.mapper.ConsoleOutboxEventReadMapper;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleOutboxCleanupResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleOutboxRepublishResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleOutboxStatsResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultConsoleOutboxOpsApplicationServiceTest {

  private ConsoleTenantGuard tenantGuard;
  private ConsoleOutboxEventReadMapper consoleOutboxEventReadMapper;
  private ConsoleOrchestratorProxyService orchestratorProxy;
  private ConsoleQueryCacheService cacheService;
  private DefaultConsoleOutboxOpsApplicationService service;

  @BeforeEach
  void setUp() {
    tenantGuard = mock(ConsoleTenantGuard.class);
    consoleOutboxEventReadMapper = mock(ConsoleOutboxEventReadMapper.class);
    orchestratorProxy = mock(ConsoleOrchestratorProxyService.class);
    cacheService = passThroughCache();
    service =
        new DefaultConsoleOutboxOpsApplicationService(
            tenantGuard,
            consoleOutboxEventReadMapper,
            mock(ConsoleRealtimeDomainEventPublisher.class),
            orchestratorProxy,
            cacheService);
  }

  private static ConsoleQueryCacheService passThroughCache() {
    ConsoleQueryCacheService cache = mock(ConsoleQueryCacheService.class);
    when(cache.<Object>getOrLoad(
            anyString(), any(), org.mockito.ArgumentMatchers.<Class<Object>>any(), any()))
        .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
    return cache;
  }

  @Test
  void shouldReturnStats() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    List<Map<String, Object>> breakdown =
        List.of(Map.of("status", "NEW", "count", 10), Map.of("status", "PUBLISHED", "count", 50));
    when(consoleOutboxEventReadMapper.statsByStatus("tenant-a")).thenReturn(breakdown);

    ConsoleOutboxStatsResponse response = service.stats("tenant-a");

    assertThat(response.tenantId()).isEqualTo("tenant-a");
    assertThat(response.statusBreakdown()).hasSize(2);
  }

  @Test
  void shouldDelegateCleanupToOrchestrator() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(orchestratorProxy.outboxCleanup("tenant-a", 30))
        .thenReturn(Map.of("published", 5, "giveUp", 3));

    ConsoleOutboxCleanupResponse response = service.cleanup("tenant-a", 30);

    assertThat(response.tenantId()).isEqualTo("tenant-a");
    assertThat(response.retainDays()).isEqualTo(30);
    assertThat(response.deletedPublished()).isEqualTo(5);
    assertThat(response.deletedGiveUp()).isEqualTo(3);
    assertThat(response.totalDeleted()).isEqualTo(8);
    verify(orchestratorProxy).outboxCleanup("tenant-a", 30);
  }

  @Test
  void shouldDelegateRepublishToOrchestrator() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    List<Long> ids = List.of(1L, 2L, 3L);
    when(orchestratorProxy.outboxRepublish("tenant-a", ids))
        .thenReturn(Map.of("requested", 3, "reset", 2));

    ConsoleOutboxRepublishResponse response = service.republish("tenant-a", ids);

    assertThat(response.tenantId()).isEqualTo("tenant-a");
    assertThat(response.requestedCount()).isEqualTo(3);
    assertThat(response.resetCount()).isEqualTo(2);
    verify(orchestratorProxy).outboxRepublish("tenant-a", ids);
  }
}
