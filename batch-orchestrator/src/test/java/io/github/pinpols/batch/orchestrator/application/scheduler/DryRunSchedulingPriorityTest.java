package io.github.pinpols.batch.orchestrator.application.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("试运行调度优先级: 试运行降级与正式任务优先级保留口径")
class DryRunSchedulingPriorityTest {

  @Test
  @DisplayName("试运行时任务优先级取最低值, 忽略配置值")
  void shouldUseLowestPriority_whenDryRun() {
    assertThat(DryRunSchedulingPriority.resolve(true, 9))
        .isEqualTo(DryRunSchedulingPriority.LOWEST_TASK_PRIORITY);
  }

  @Test
  @DisplayName("正式任务保留配置的优先级")
  void shouldKeepConfiguredPriority_whenFormalTask() {
    assertThat(DryRunSchedulingPriority.resolve(false, 3)).isEqualTo(3);
  }

  @Test
  @DisplayName("正式任务未配置优先级时返回空, 不发生拆箱异常")
  void shouldKeepNullPriority_whenFormalTaskHasNoPriority() {
    assertThat(DryRunSchedulingPriority.resolve(false, null)).isNull();
  }
}
