package io.github.pinpols.batch.trigger.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.trigger.domain.query.ExpiredReadinessWaitRow;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.mapper.ScheduledOccurrenceMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

@DisplayName("readiness 等待扫尾: 无 Quartz 回调时按 CAS 终结超窗等待")
class ReadinessWaitSweeperTest {

  private static final String METRIC_TIMEOUT = "batch.trigger.readiness.wait.timeout.total";

  private ScheduledOccurrenceMapper occurrenceMapper;
  private TriggerGracefulShutdown gracefulShutdown;
  private SimpleMeterRegistry meterRegistry;
  private ReadinessWaitSweeper sweeper;

  @BeforeEach
  void setUp() {
    occurrenceMapper = mock(ScheduledOccurrenceMapper.class);
    gracefulShutdown = mock(TriggerGracefulShutdown.class);
    meterRegistry = new SimpleMeterRegistry();
    sweeper = new ReadinessWaitSweeper(occurrenceMapper, gracefulShutdown, meterRegistry);
  }

  @Test
  @DisplayName("超窗等待逐个 CAS 终结为 READINESS_TIMEOUT 并计指标")
  void shouldTransitionExpiredWaitsToTimeout() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(occurrenceMapper.selectExpiredReadinessWaits(any(Instant.class), anyInt()))
        .thenReturn(List.of(row("tenant-a", 7L), row("tenant-b", 8L)));
    when(occurrenceMapper.timeoutExpiredReadinessWait(
            any(), any(), ArgumentMatchers.eq("READINESS_TIMEOUT")))
        .thenReturn(1);

    sweeper.sweep();

    ArgumentCaptor<Instant> scannedAt = ArgumentCaptor.forClass(Instant.class);
    verify(occurrenceMapper).selectExpiredReadinessWaits(scannedAt.capture(), anyInt());
    verify(occurrenceMapper, Mockito.times(2))
        .timeoutExpiredReadinessWait(
            any(),
            ArgumentMatchers.eq(scannedAt.getValue()),
            ArgumentMatchers.eq("READINESS_TIMEOUT"));
    assertThat(meterRegistry.counter(METRIC_TIMEOUT).count()).isEqualTo(2.0);
  }

  @Test
  @DisplayName("Quartz 已推进(CAS 返回 0)时不重复终结、不计超时指标")
  void shouldSkipWhenQuartzAlreadyAdvancedTheOccurrence() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(occurrenceMapper.selectExpiredReadinessWaits(any(Instant.class), anyInt()))
        .thenReturn(List.of(row("tenant-a", 7L)));
    when(occurrenceMapper.timeoutExpiredReadinessWait(
            any(), any(), ArgumentMatchers.eq("READINESS_TIMEOUT")))
        .thenReturn(0);

    sweeper.sweep();

    verify(occurrenceMapper)
        .timeoutExpiredReadinessWait(any(), any(), ArgumentMatchers.eq("READINESS_TIMEOUT"));
    assertThat(meterRegistry.counter(METRIC_TIMEOUT).count()).isZero();
  }

  @Test
  @DisplayName("停机/无到期记录时不查询不写状态")
  void shouldDoNothingWhenDrainingOrNoExpiredWait() {
    when(gracefulShutdown.isDraining()).thenReturn(true);
    sweeper.sweep();
    verify(occurrenceMapper, never()).selectExpiredReadinessWaits(any(Instant.class), anyInt());

    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(occurrenceMapper.selectExpiredReadinessWaits(any(Instant.class), anyInt()))
        .thenReturn(List.of());
    sweeper.sweep();
    verify(occurrenceMapper, never())
        .timeoutExpiredReadinessWait(any(), any(), ArgumentMatchers.eq("READINESS_TIMEOUT"));
  }

  private static ExpiredReadinessWaitRow row(String tenantId, Long workflowDefinitionId) {
    ExpiredReadinessWaitRow row = new ExpiredReadinessWaitRow();
    row.setTenantId(tenantId);
    row.setWorkflowDefinitionId(workflowDefinitionId);
    row.setScheduledFireTime(Instant.parse("2026-10-24T00:00:00Z"));
    row.setDeadline(Instant.parse("2026-10-24T02:00:00Z"));
    return row;
  }
}
