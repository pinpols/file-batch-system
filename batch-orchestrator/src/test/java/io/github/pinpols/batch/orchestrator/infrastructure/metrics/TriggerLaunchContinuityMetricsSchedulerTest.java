package io.github.pinpols.batch.orchestrator.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.config.TriggerLaunchContinuityMetricsProperties;
import io.github.pinpols.batch.orchestrator.domain.query.TriggerLaunchContinuityMetrics;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

@DisplayName("触发连续性指标的聚合与失败采样")
class TriggerLaunchContinuityMetricsSchedulerTest {

  @DisplayName("发布全局积压与启动延迟且不使用高基数标签")
  @Test
  void shouldPublishGlobalBacklogAndLaunchLatencyGaugesWithoutHighCardinalityTags() {
    TriggerRequestMapper mapper = mock(TriggerRequestMapper.class);
    OrchestratorGracefulShutdown shutdown = mock(OrchestratorGracefulShutdown.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(shutdown.isDraining()).thenReturn(false);
    when(mapper.selectLaunchContinuityMetrics(ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(new TriggerLaunchContinuityMetrics(2L, 3_600L, 50L, 4.25));
    TriggerLaunchContinuityMetricsScheduler scheduler = new TriggerLaunchContinuityMetricsScheduler(
        mapper, registry, shutdown, new TriggerLaunchContinuityMetricsProperties());
    scheduler.initializeMeters();

    scheduler.sample();

    assertThat(registry.get("batch.trigger.launch.stale_accepted.count").gauge().value())
        .isEqualTo(2.0);
    assertThat(registry
            .get("batch.trigger.launch.stale_accepted.oldest_age.seconds")
            .gauge()
            .value())
        .isEqualTo(3_600.0);
    assertThat(registry
            .get("batch.trigger.launch.request_to_instance.p95.seconds")
            .gauge()
            .value())
        .isEqualTo(4.25);
    assertThat(registry
            .get("batch.trigger.launch.stale_accepted.count")
            .gauge()
            .getId()
            .getTags())
        .isEmpty();
  }

  @DisplayName("采样失败保留连续性指标快照")
  @Test
  void shouldKeepLastSnapshotWhenSamplingFails() {
    TriggerRequestMapper mapper = mock(TriggerRequestMapper.class);
    OrchestratorGracefulShutdown shutdown = mock(OrchestratorGracefulShutdown.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(shutdown.isDraining()).thenReturn(false);
    when(mapper.selectLaunchContinuityMetrics(ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(new TriggerLaunchContinuityMetrics(1L, 600L, 25L, 3.0))
        .thenThrow(new IllegalStateException("database unavailable"));
    TriggerLaunchContinuityMetricsScheduler scheduler = new TriggerLaunchContinuityMetricsScheduler(
        mapper, registry, shutdown, new TriggerLaunchContinuityMetricsProperties());
    scheduler.initializeMeters();

    scheduler.sample();
    scheduler.sample();

    assertThat(registry.get("batch.trigger.launch.stale_accepted.count").gauge().value())
        .isEqualTo(1.0);
    assertThat(registry
            .get("batch.trigger.launch.continuity.metrics.failures")
            .counter()
            .count())
        .isEqualTo(1.0);
  }
}
