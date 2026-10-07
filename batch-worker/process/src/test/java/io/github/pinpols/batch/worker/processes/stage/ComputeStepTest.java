package io.github.pinpols.batch.worker.processes.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P2-B 后,ComputeStep 不再持有 plugin 列表;plugin 解析在 DefaultProcessStageExecutor 启动时完成并 stash 到
 * context.resolvedPlugin。本测试只验 ComputeStep 的薄委托语义。
 */
@DisplayName("处理计算阶段:薄委托给已解析插件,插件返回空与未解析插件时的语义,以及试运行跳过副作用")
class ComputeStepTest {

  @Test
  @DisplayName("上下文已解析插件时把计算委托给插件,并返回插件的阶段结果")
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
  @DisplayName("插件计算返回空结果时判定失败,并给出空结果错误码")
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
  @DisplayName("上下文未解析插件时空操作成功,处理数置零")
  void execute_succeedsAsNoOp_whenNoPluginResolved() {
    ComputeStep step = new ComputeStep();
    ProcessJobContext context = new ProcessJobContext();

    ProcessStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes()).containsEntry("processedCount", 0);
  }

  @Test
  @DisplayName("试运行模式下计算与校验都成功但不调用插件:处理数置零,并记录被跳过的暂存写入")
  void shouldSkipComputeAndValidateSideEffects_whenDryRun() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    ProcessJobContext context = new ProcessJobContext();
    context.setResolvedPlugin(plugin);
    context.getAttributes().put(PipelineRuntimeKeys.DRY_RUN, true);

    ProcessStageResult computeResult = new ComputeStep().execute(context);
    ProcessStageResult validateResult = new ValidateStep().execute(context);

    assertThat(computeResult.success()).isTrue();
    assertThat(validateResult.success()).isTrue();
    assertThat(context.getAttributes())
        .containsEntry("processedCount", 0)
        .containsEntry(PipelineRuntimeKeys.DRY_RUN_SKIPPED, "PROCESS_STAGING_WRITE");
    verify(plugin, never()).compute(any());
    verify(plugin, never()).validate(any());
  }
}
