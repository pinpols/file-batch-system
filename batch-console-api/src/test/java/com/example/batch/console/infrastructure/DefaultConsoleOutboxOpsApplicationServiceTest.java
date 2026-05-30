package com.example.batch.console.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.console.domain.ops.application.ConsoleOrchestratorProxyService;
import com.example.batch.console.domain.ops.infrastructure.DefaultConsoleOutboxOpsApplicationService;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.domain.ops.mapper.OutboxEventMapper;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.domain.ops.web.response.ConsoleOutboxCleanupResponse;
import com.example.batch.console.domain.ops.web.response.ConsoleOutboxRepublishResponse;
import com.example.batch.console.domain.ops.web.response.ConsoleOutboxStatsResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultConsoleOutboxOpsApplicationServiceTest {

  private ConsoleTenantGuard tenantGuard;
  private OutboxEventMapper outboxEventMapper;
  private ConsoleOrchestratorProxyService orchestratorProxy;
  private DefaultConsoleOutboxOpsApplicationService service;

  @BeforeEach
  void setUp() {
    tenantGuard = mock(ConsoleTenantGuard.class);
    outboxEventMapper = mock(OutboxEventMapper.class);
    orchestratorProxy = mock(ConsoleOrchestratorProxyService.class);
    service =
        new DefaultConsoleOutboxOpsApplicationService(
            tenantGuard,
            outboxEventMapper,
            mock(ConsoleRealtimeDomainEventPublisher.class),
            orchestratorProxy);
  }

  @Test
  void shouldReturnStats() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    List<Map<String, Object>> breakdown =
        List.of(Map.of("status", "NEW", "count", 10), Map.of("status", "PUBLISHED", "count", 50));
    when(outboxEventMapper.statsByStatus("tenant-a")).thenReturn(breakdown);

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
