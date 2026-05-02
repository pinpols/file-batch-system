package com.example.batch.orchestrator.application.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.domain.entity.TriggerRequestLaunchReconcileRow;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ADR-010 Stage 6 reconciler 单测,覆盖 5 类路径:
 *
 * <ol>
 *   <li>draining 时直接返回,不查不写
 *   <li>无候选行时静默返回
 *   <li>正常路径:每行 CAS 成功 → reconciled counter +1
 *   <li>CAS miss(并发已被 writeBack 改走)→ skipped_cas counter +1,不打 ERROR
 *   <li>单行抛异常:不拖累整批,继续处理后续行
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class TriggerRequestLaunchReconcilerTest {

  @Mock private TriggerRequestMapper triggerRequestMapper;
  @Mock private OrchestratorGracefulShutdown gracefulShutdown;

  private SimpleMeterRegistry meterRegistry;
  private TriggerRequestLaunchReconciler reconciler;

  @BeforeEach
  void setUp() throws Exception {
    meterRegistry = new SimpleMeterRegistry();
    reconciler =
        new TriggerRequestLaunchReconciler(triggerRequestMapper, gracefulShutdown, meterRegistry);
    setField(reconciler, "minAgeSeconds", 300);
    setField(reconciler, "batchSize", 200);
  }

  @Test
  void reconcile_skipsWhenDraining() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    reconciler.reconcile();

    verify(triggerRequestMapper, never()).selectStaleAcceptedWithJobInstance(any(), anyInt());
    verify(triggerRequestMapper, never()).reconcileLaunched(any(), any(), any());
  }

  @Test
  void reconcile_noCandidates_returnsQuietly() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(triggerRequestMapper.selectStaleAcceptedWithJobInstance(any(Instant.class), eq(200)))
        .thenReturn(List.of());

    reconciler.reconcile();

    verify(triggerRequestMapper, never()).reconcileLaunched(any(), any(), any());
  }

  @Test
  void reconcile_happyPath_writesBackAndIncrementsCounter() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    TriggerRequestLaunchReconcileRow row1 = row("tenant-a", "req-1", 101L);
    TriggerRequestLaunchReconcileRow row2 = row("tenant-a", "req-2", 102L);
    when(triggerRequestMapper.selectStaleAcceptedWithJobInstance(any(Instant.class), eq(200)))
        .thenReturn(List.of(row1, row2));
    when(triggerRequestMapper.reconcileLaunched("tenant-a", "req-1", 101L)).thenReturn(1);
    when(triggerRequestMapper.reconcileLaunched("tenant-a", "req-2", 102L)).thenReturn(1);

    reconciler.reconcile();

    verify(triggerRequestMapper, times(2)).reconcileLaunched(any(), any(), any());
    assertThat(reconciledCount("tenant-a")).isEqualTo(2.0);
    assertThat(skippedCount("tenant-a")).isEqualTo(0.0);
  }

  @Test
  void reconcile_casMiss_incrementsSkippedCounter() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    TriggerRequestLaunchReconcileRow row = row("tenant-b", "req-conc", 201L);
    when(triggerRequestMapper.selectStaleAcceptedWithJobInstance(any(Instant.class), eq(200)))
        .thenReturn(List.of(row));
    // CAS 返回 0 = 并发已被 consumer writeBack 改走
    when(triggerRequestMapper.reconcileLaunched("tenant-b", "req-conc", 201L)).thenReturn(0);

    reconciler.reconcile();

    assertThat(reconciledCount("tenant-b")).isEqualTo(0.0);
    assertThat(skippedCount("tenant-b")).isEqualTo(1.0);
  }

  @Test
  void reconcile_singleRowThrows_continuesBatch() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    TriggerRequestLaunchReconcileRow bad = row("tenant-c", "req-bad", 301L);
    TriggerRequestLaunchReconcileRow good = row("tenant-c", "req-good", 302L);
    when(triggerRequestMapper.selectStaleAcceptedWithJobInstance(any(Instant.class), eq(200)))
        .thenReturn(List.of(bad, good));
    when(triggerRequestMapper.reconcileLaunched("tenant-c", "req-bad", 301L))
        .thenThrow(new IllegalStateException("DB transient down"));
    when(triggerRequestMapper.reconcileLaunched("tenant-c", "req-good", 302L)).thenReturn(1);

    reconciler.reconcile();

    // 第一行抛了,第二行仍要处理
    verify(triggerRequestMapper).reconcileLaunched("tenant-c", "req-good", 302L);
    assertThat(reconciledCount("tenant-c")).isEqualTo(1.0);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static TriggerRequestLaunchReconcileRow row(
      String tenantId, String requestId, Long jobInstanceId) {
    TriggerRequestLaunchReconcileRow r = new TriggerRequestLaunchReconcileRow();
    r.setTenantId(tenantId);
    r.setRequestId(requestId);
    r.setJobInstanceId(jobInstanceId);
    return r;
  }

  private double reconciledCount(String tenant) {
    return meterRegistry.counter("batch.trigger.launch.reconciled.total", "tenant", tenant).count();
  }

  private double skippedCount(String tenant) {
    return meterRegistry
        .counter("batch.trigger.launch.reconciled.skipped.total", "tenant", tenant)
        .count();
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }
}
