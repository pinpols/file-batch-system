package io.github.pinpols.batch.orchestrator.application.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DryRunSchedulingPriorityTest {

  @Test
  void dryRunAlwaysUsesLowestTaskPriority() {
    assertThat(DryRunSchedulingPriority.resolve(true, 9))
        .isEqualTo(DryRunSchedulingPriority.LOWEST_TASK_PRIORITY);
  }

  @Test
  void formalTaskKeepsConfiguredPriority() {
    assertThat(DryRunSchedulingPriority.resolve(false, 3)).isEqualTo(3);
  }
}
