package com.example.batch.console.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.console.mapper.OutboxEventMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.response.ConsoleOutboxCleanupResponse;
import com.example.batch.console.web.response.ConsoleOutboxRepublishResponse;
import com.example.batch.console.web.response.ConsoleOutboxStatsResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultConsoleOutboxOpsApplicationServiceTest {

  private ConsoleTenantGuard tenantGuard;
  private OutboxEventMapper outboxEventMapper;
  private DefaultConsoleOutboxOpsApplicationService service;

  @BeforeEach
  void setUp() {
    tenantGuard = mock(ConsoleTenantGuard.class);
    outboxEventMapper = mock(OutboxEventMapper.class);
    service =
        new DefaultConsoleOutboxOpsApplicationService(
            tenantGuard,
            outboxEventMapper,
            mock(
                com.example.batch.console.infrastructure.realtime
                    .ConsoleRealtimeDomainEventPublisher.class));
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
  void shouldCleanupOldEvents() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(outboxEventMapper.deletePublishedBefore(eq("tenant-a"), any(Instant.class))).thenReturn(5);
    when(outboxEventMapper.deleteGiveUpBefore(eq("tenant-a"), any(Instant.class))).thenReturn(3);

    ConsoleOutboxCleanupResponse response = service.cleanup("tenant-a", 30);

    assertThat(response.tenantId()).isEqualTo("tenant-a");
    assertThat(response.retainDays()).isEqualTo(30);
    assertThat(response.deletedPublished()).isEqualTo(5);
    assertThat(response.deletedGiveUp()).isEqualTo(3);
    assertThat(response.totalDeleted()).isEqualTo(8);
  }

  @Test
  void shouldRepublishEvents() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    List<Long> ids = List.of(1L, 2L, 3L);
    when(outboxEventMapper.resetToNew("tenant-a", ids, List.of("FAILED", "GIVE_UP"))).thenReturn(2);

    ConsoleOutboxRepublishResponse response = service.republish("tenant-a", ids);

    assertThat(response.tenantId()).isEqualTo("tenant-a");
    assertThat(response.requestedCount()).isEqualTo(3);
    assertThat(response.resetCount()).isEqualTo(2);
  }
}
