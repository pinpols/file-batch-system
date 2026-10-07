package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("任务结果状态策略: 实例与流程终态判定, 活跃节点解析与失败提升口径")
class TaskOutcomeStatePolicyTest {

  @Test
  @DisplayName("按成功与失败分片数解析实例终态, 并保持试运行语义不变")
  void shouldResolveInstanceTerminalStates_whenCountsVary() {
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
  @DisplayName("按结果解析流程状态与当前节点")
  void shouldResolveWorkflowStateAndCurrentNode_whenOutcomeGiven() {
    assertThat(TaskOutcomeStatePolicy.resolveWorkflowEvent(0, true, false, false))
        .isEqualTo(WorkflowRunStatus.SUCCESS.code());
    assertThat(TaskOutcomeStatePolicy.resolveWorkflowEvent(1, true, false, true))
        .isEqualTo(WorkflowRunStatus.FAILED_DRY_RUN.code());
    assertThat(TaskOutcomeStatePolicy.resolveWorkflowCurrentNode(
            Set.of(), WorkflowRunStatus.SUCCESS.code(), "CURRENT"))
        .isEqualTo(WorkflowNodeCode.END.code());
    assertThat(TaskOutcomeStatePolicy.resolveWorkflowCurrentNode(
            Set.of("B", "A"), WorkflowRunStatus.RUNNING.code(), "CURRENT"))
        .isIn("B,A", "A,B");
  }

  @Test
  @DisplayName("解析活跃节点时去重去空白, 并正确识别试运行与终态")
  void shouldParseActiveNodesAndRecognizeStates_whenInputGiven() {
    assertThat(TaskOutcomeStatePolicy.parseActiveNodes(" A, B, A,  ")).containsExactly("A", "B");
    assertThat(TaskOutcomeStatePolicy.isTerminalJobInstanceStatus("RUNNING")).isFalse();
    assertThat(TaskOutcomeStatePolicy.isTerminalJobInstanceStatus(
            JobInstanceStatus.PARTIAL_FAILED.code()))
        .isTrue();

    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setDryRun(true);
    assertThat(TaskOutcomeStatePolicy.isDryRun(instance)).isTrue();
    assertThat(TaskOutcomeStatePolicy.isDryRun(null)).isFalse();
  }

  @Test
  @DisplayName("仅当实例停留失败且全部分片成功时才提升为成功终态")
  void shouldPromote_whenStaleFailureAndAllPartitionsSucceeded() {
    assertThat(TaskOutcomeStatePolicy.shouldPromoteTerminalFailure(
            "FAILED", "SUCCESS", 1, 0, true, false))
        .isTrue();
    assertThat(TaskOutcomeStatePolicy.shouldPromoteTerminalFailure(
            "PARTIAL_FAILED", "SUCCESS", 1, 0, true, false))
        .isTrue();
    assertThat(TaskOutcomeStatePolicy.shouldPromoteTerminalFailure(
            "FAILED", "SUCCESS", 1, 1, true, false))
        .isFalse();
    assertThat(TaskOutcomeStatePolicy.shouldPromoteTerminalFailure(
            "FAILED", "SUCCESS", 1, 0, false, false))
        .isFalse();
    assertThat(TaskOutcomeStatePolicy.shouldPromoteTerminalFailure(
            "CANCELLED", "SUCCESS", 1, 0, true, false))
        .isFalse();
  }
}
