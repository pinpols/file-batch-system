package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WorkflowRunStatus 工作流运行状态:活跃与终态分类保持完备")
class WorkflowRunStatusTest {

  @Test
  @DisplayName("活跃与终态互斥且覆盖全部状态,暂停归入活跃")
  void shouldClassifyEveryStatusExactlyOnce_whenCheckingLifecycle() {
    Set<String> allCodes = Arrays.stream(WorkflowRunStatus.values())
        .map(WorkflowRunStatus::code)
        .collect(Collectors.toSet());

    assertThat(WorkflowRunStatus.terminalCodes())
        .doesNotContainAnyElementsOf(WorkflowRunStatus.activeCodes());
    assertThat(WorkflowRunStatus.terminalCodes())
        .containsExactlyInAnyOrder(
            WorkflowRunStatus.SUCCESS.code(),
            WorkflowRunStatus.FAILED.code(),
            WorkflowRunStatus.TERMINATED.code(),
            WorkflowRunStatus.SUCCESS_DRY_RUN.code(),
            WorkflowRunStatus.FAILED_DRY_RUN.code());
    assertThat(WorkflowRunStatus.activeCodes()).contains(WorkflowRunStatus.PAUSED.code());
    assertThat(WorkflowRunStatus.terminalCodes())
        .withFailMessage("活跃与终态并集必须覆盖所有工作流状态")
        .containsExactlyInAnyOrderElementsOf(allCodes.stream()
            .filter(code -> !WorkflowRunStatus.activeCodes().contains(code))
            .collect(Collectors.toSet()));
  }
}
