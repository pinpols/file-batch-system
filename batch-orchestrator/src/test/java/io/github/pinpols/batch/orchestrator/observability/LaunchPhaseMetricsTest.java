package io.github.pinpols.batch.orchestrator.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.orchestrator.observability.LaunchPhaseMetrics.Phase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class LaunchPhaseMetricsTest {

  @Test
  void recordsSuccessfulAndFailedActionsWithoutChangingOutcome() {
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
