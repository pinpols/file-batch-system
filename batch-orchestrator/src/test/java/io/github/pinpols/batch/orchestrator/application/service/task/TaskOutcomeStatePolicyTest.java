package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskOutcomeStatePolicyTest {

  @Test
  void resolvesInstanceTerminalStatesWithoutChangingDryRunSemantics() {
    assertThat(TaskOutcomeStatePolicy.resolveInstanceEvent(0, 0, false, false, false))
        .isEqualTo(JobInstanceStatus.RUNNING.code());
    assertThat(TaskOutcomeStatePolicy.resolveInstanceEvent(1, 0, true, true, false))
        .isEqualTo(JobInstanceStatus.RUNNING.code());
    assertThat(TaskOutcomeStatePolicy.resolveInstanceEvent(1, 1, true, false, false))
        .isEqualTo(JobInstanceStatus.PARTIAL_FAILED.code());
    assertThat(TaskOutcomeStatePolicy.resolveInstanceEvent(1, 1, true, false, true))
        .isEqualTo(JobInstanceStatus.FAILED_DRY_RUN.code());
    assertThat(TaskOutcomeStatePolicy.resolveInstanceEvent(0, 1, true, false, false))
        .isEqualTo(JobInstanceStatus.FAILED.code());
    assertThat(TaskOutcomeStatePolicy.resolveInstanceEvent(1, 0, true, false, true))
        .isEqualTo(JobInstanceStatus.SUCCESS_DRY_RUN.code());
  }

  @Test
  void resolvesWorkflowStateAndCurrentNode() {
    assertThat(TaskOutcomeStatePolicy.resolveWorkflowEvent(0, true, false, false))
        .isEqualTo(WorkflowRunStatus.SUCCESS.code());
    assertThat(TaskOutcomeStatePolicy.resolveWorkflowEvent(1, true, false, true))
        .isEqualTo(WorkflowRunStatus.FAILED_DRY_RUN.code());
    assertThat(
            TaskOutcomeStatePolicy.resolveWorkflowCurrentNode(
                Set.of(), WorkflowRunStatus.SUCCESS.code(), "CURRENT"))
        .isEqualTo(WorkflowNodeCode.END.code());
    assertThat(
            TaskOutcomeStatePolicy.resolveWorkflowCurrentNode(
                Set.of("B", "A"), WorkflowRunStatus.RUNNING.code(), "CURRENT"))
        .isIn("B,A", "A,B");
  }

  @Test
  void parsesActiveNodesAndRecognizesDryRunAndTerminalStates() {
    assertThat(TaskOutcomeStatePolicy.parseActiveNodes(" A, B, A,  ")).containsExactly("A", "B");
    assertThat(TaskOutcomeStatePolicy.isTerminalJobInstanceStatus("RUNNING")).isFalse();
    assertThat(
            TaskOutcomeStatePolicy.isTerminalJobInstanceStatus(
                JobInstanceStatus.PARTIAL_FAILED.code()))
        .isTrue();

    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setDryRun(true);
    assertThat(TaskOutcomeStatePolicy.isDryRun(instance)).isTrue();
    assertThat(TaskOutcomeStatePolicy.isDryRun(null)).isFalse();
  }
}
