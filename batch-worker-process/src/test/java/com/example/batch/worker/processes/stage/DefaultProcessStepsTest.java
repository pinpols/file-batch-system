package com.example.batch.worker.processes.stage;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessStage;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultProcessStepsTest {

  @Test
  void defaultSteps_coverAllProcessStages() {
    List<ProcessStageStep> steps =
        List.of(
            new PrepareStep(),
            new ComputeStep(),
            new ValidateStep(),
            new CommitStep(),
            new FeedbackStep());

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
  void defaultNonComputeSteps_areNoopSuccess() {
    ProcessJobContext context = new ProcessJobContext();

    assertThat(new PrepareStep().execute(context).success()).isTrue();
    assertThat(new ValidateStep().execute(context).success()).isTrue();
    assertThat(new CommitStep().execute(context).success()).isTrue();
    assertThat(new FeedbackStep().execute(context).success()).isTrue();
  }
}
