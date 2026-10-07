package io.github.pinpols.batch.orchestrator.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.orchestrator.observability.LaunchPhaseMetrics.Phase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("启动阶段耗时指标的记录行为,覆盖成功与失败路径的计时器计数及结果透传")
class LaunchPhaseMetricsTest {

  @Test
  @DisplayName("记录成功与失败的阶段耗时,同时保持原返回值并把异常原样抛出")
  void shouldRecordTimingsAndRethrowFailure_whenRecordingSuccessOrFailure() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    LaunchPhaseMetrics metrics = new LaunchPhaseMetrics(registry);

    assertThat(metrics.record(Phase.VALIDATION, () -> "ok")).isEqualTo("ok");
    assertThatThrownBy(() -> metrics.record(Phase.DISPATCH_TRANSACTION, () -> {
          throw new IllegalStateException("failed");
        }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(timerCount(registry, "validation")).isEqualTo(1L);
    assertThat(timerCount(registry, "dispatch_transaction")).isEqualTo(1L);
  }

  private static long timerCount(SimpleMeterRegistry registry, String phase) {
    return registry
        .get("batch.orchestrator.launch.phase.duration")
        .tag("phase", phase)
        .timer()
        .count();
  }
}
