package com.example.batch.worker.processes.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessStage;
import com.example.batch.worker.processes.domain.ProcessStageResult;
import org.junit.jupiter.api.Test;

/**
 * P2-B 后,ComputeStep 不再持有 plugin 列表;plugin 解析在 DefaultProcessStageExecutor 启动时完成并 stash 到
 * context.resolvedPlugin。本测试只验 ComputeStep 的薄委托语义。
 */
class ComputeStepTest {

  @Test
  void execute_invokesResolvedPluginFromContext() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.compute(any())).thenReturn(ProcessStageResult.success(ProcessStage.COMPUTE));

    ComputeStep step = new ComputeStep();
    ProcessJobContext context = new ProcessJobContext();
    context.setResolvedPlugin(plugin);

    ProcessStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(result.stage()).isEqualTo(ProcessStage.COMPUTE);
    verify(plugin).compute(context);
  }

  @Test
  void execute_returnsFailure_whenPluginReturnsNull() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.compute(any())).thenReturn(null);

    ComputeStep step = new ComputeStep();
    ProcessJobContext context = new ProcessJobContext();
    context.setResolvedPlugin(plugin);

    ProcessStageResult result = step.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("PROCESS_COMPUTE_EMPTY_RESULT");
  }

  @Test
  void execute_succeedsAsNoOp_whenNoPluginResolved() {
    ComputeStep step = new ComputeStep();
    ProcessJobContext context = new ProcessJobContext();

    ProcessStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes()).containsEntry("processedCount", 0);
  }
}
