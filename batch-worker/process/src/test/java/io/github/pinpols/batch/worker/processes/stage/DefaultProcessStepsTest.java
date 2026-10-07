package io.github.pinpols.batch.worker.processes.stage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.metrics.ProcessMetrics;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("处理默认阶段步骤:五个默认步骤覆盖全部阶段,非计算步骤为空操作且直接成功")
class DefaultProcessStepsTest {

  @Test
  @DisplayName("默认步骤按准备、计算、校验、提交、反馈顺序覆盖全部阶段,步骤码统一带处理前缀")
  void defaultSteps_coverAllProcessStages() {
    List<ProcessStageStep> steps = List.of(
        new PrepareStep(),
        new ComputeStep(),
        new ValidateStep(),
        new CommitStep(),
        new FeedbackStep(ProcessMetrics.noop()));

    assertThat(steps)
        .extracting(ProcessStageStep::stage)
        .containsExactly(
            ProcessStage.PREPARE,
            ProcessStage.COMPUTE,
            ProcessStage.VALIDATE,
            ProcessStage.COMMIT,
            ProcessStage.FEEDBACK);
    assertThat(steps)
        .extracting(ProcessStageStep::stepCode)
        .allMatch(code -> code.startsWith("PROCESS_"));
  }

  @Test
  @DisplayName("准备、校验、提交与反馈四个非计算步骤在空上下文下都直接成功")
  void defaultNonComputeSteps_areNoopSuccess() {
    ProcessJobContext context = new ProcessJobContext();

    assertThat(new PrepareStep().execute(context).success()).isTrue();
    assertThat(new ValidateStep().execute(context).success()).isTrue();
    assertThat(new CommitStep().execute(context).success()).isTrue();
    assertThat(new FeedbackStep(ProcessMetrics.noop()).execute(context).success())
        .isTrue();
  }
}
