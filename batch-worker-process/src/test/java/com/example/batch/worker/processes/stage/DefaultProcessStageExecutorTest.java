package com.example.batch.worker.processes.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.StageFailureCode;
import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessStage;
import com.example.batch.worker.processes.domain.ProcessStageResult;
import com.example.batch.worker.processes.metrics.ProcessMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultProcessStageExecutorTest {

  @Mock private PlatformFileRuntimeRepository runtimeRepository;

  private static final Long PIPELINE_INSTANCE_ID = 100L;
  private static final Long STEP_RUN_ID = 200L;

  @BeforeEach
  void setUp() {
    when(runtimeRepository.toLong(any())).thenReturn(PIPELINE_INSTANCE_ID);
    when(runtimeRepository.startStepRun(any(), any(), any(), any())).thenReturn(STEP_RUN_ID);
  }

  @Test
  void defaultStepDefinitions_useWapBookendsOrder() {
    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(), List.of(), runtimeRepository, ProcessMetrics.noop());

    List<PipelineStepTemplate> templates = executor.defaultStepDefinitions();

    assertThat(templates)
        .extracting(PipelineStepTemplate::stageCode)
        .containsExactly("PREPARE", "COMPUTE", "VALIDATE", "COMMIT", "FEEDBACK");
    assertThat(templates)
        .extracting(PipelineStepTemplate::stepCode)
        .containsExactly(
            "PROCESS_PREPARE",
            "PROCESS_COMPUTE",
            "PROCESS_VALIDATE",
            "PROCESS_COMMIT",
            "PROCESS_FEEDBACK");
  }

  @Test
  void execute_runsAll5Stages_inOrder_whenPipelineDeclared() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.implCode()).thenReturn("dailySummary");
    when(plugin.compute(any()))
        .thenAnswer(
            inv -> {
              ProcessJobContext ctx = inv.getArgument(0);
              ctx.getAttributes().put("processedCount", 7);
              return ProcessStageResult.success(ProcessStage.COMPUTE);
            });

    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(), List.of(plugin), runtimeRepository, ProcessMetrics.noop());

    ProcessJobContext context = newContext();
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, fullPipelineWith("dailySummary"));

    List<ProcessStageResult> results = executor.execute(context);

    assertThat(results).hasSize(5);
    assertThat(results)
        .extracting(ProcessStageResult::stage)
        .containsExactly(
            ProcessStage.PREPARE,
            ProcessStage.COMPUTE,
            ProcessStage.VALIDATE,
            ProcessStage.COMMIT,
            ProcessStage.FEEDBACK);
    assertThat(results).allMatch(ProcessStageResult::success);
    assertThat(context.getResolvedPlugin()).isSameAs(plugin);
    assertThat(context.getBatchKey()).isNotBlank();
    assertThat(context.getAttributes()).containsEntry("processedCount", 7);
    verify(plugin).prepare(context);
    verify(plugin).compute(context);
    verify(plugin).validate(context);
    verify(plugin).commit(context);
    verify(plugin).feedback(context);
  }

  @Test
  void execute_skipsRemainingStages_whenComputeFails() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.implCode()).thenReturn("failingPlugin");
    when(plugin.compute(any()))
        .thenReturn(ProcessStageResult.failure(ProcessStage.COMPUTE, "ERR", "boom"));

    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(), List.of(plugin), runtimeRepository, ProcessMetrics.noop());

    ProcessJobContext context = newContext();
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, fullPipelineWith("failingPlugin"));

    List<ProcessStageResult> results = executor.execute(context);

    assertThat(results).hasSize(2);
    assertThat(results.get(1).success()).isFalse();
    assertThat(results.get(1).stage()).isEqualTo(ProcessStage.COMPUTE);
    verify(plugin).prepare(context);
    verify(plugin).compute(context);
    verify(plugin, never()).validate(any());
    verify(plugin, never()).commit(any());
    verify(plugin, never()).feedback(any());
  }

  @Test
  void execute_returnsBusinessError_whenPluginPrepareThrowsBizException() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.implCode()).thenReturn("p1");
    org.mockito.BDDMockito.willThrow(new BizException(ResultCode.INVALID_ARGUMENT, "bad spec"))
        .given(plugin)
        .prepare(any());

    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(), List.of(plugin), runtimeRepository, ProcessMetrics.noop());

    ProcessJobContext context = newContext();
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, fullPipelineWith("p1"));

    List<ProcessStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.BUSINESS_ERROR.name());
    assertThat(results.get(0).message()).isEqualTo("bad spec");
    verify(runtimeRepository).finishStepRunFailure(eq(STEP_RUN_ID), any(), any(), any());
  }

  @Test
  void execute_returnsInfraError_whenPluginPrepareThrowsRuntimeException() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.implCode()).thenReturn("p1");
    org.mockito.BDDMockito.willThrow(new RuntimeException("io fail")).given(plugin).prepare(any());

    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(), List.of(plugin), runtimeRepository, ProcessMetrics.noop());

    ProcessJobContext context = newContext();
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, fullPipelineWith("p1"));

    List<ProcessStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.INFRA_ERROR.name());
    assertThat(results.get(0).message()).isEqualTo("io fail");
  }

  @Test
  void execute_resolvesPluginViaPayloadFallback_whenComputeStepImplCodeIsSentinel() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.implCode()).thenReturn("payloadDriven");
    when(plugin.compute(any())).thenReturn(ProcessStageResult.success(ProcessStage.COMPUTE));

    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(), List.of(plugin), runtimeRepository, ProcessMetrics.noop());

    ProcessJobContext context = newContext();
    // COMPUTE step impl_code 是默认 sentinel(PROCESS_COMPUTE),走 payload 注入的 processImplCode 兜底
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, fullPipelineWith("PROCESS_COMPUTE"));
    context.getAttributes().put("processImplCode", "payloadDriven");

    executor.execute(context);

    assertThat(context.getResolvedPlugin()).isSameAs(plugin);
    verify(plugin).compute(context);
  }

  @Test
  void execute_runsAll5StagesAsNoOp_whenNoPluginConfigured() {
    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(), List.of(), runtimeRepository, ProcessMetrics.noop());

    ProcessJobContext context = newContext();
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, fullPipelineWith("PROCESS_COMPUTE"));

    List<ProcessStageResult> results = executor.execute(context);

    assertThat(results).hasSize(5);
    assertThat(results).allMatch(ProcessStageResult::success);
    assertThat(context.getResolvedPlugin()).isNull();
    assertThat(context.getAttributes()).containsEntry("processedCount", 0);
  }

  @Test
  void execute_returnsPipelineStepMissing_whenNoStepsConfigured() {
    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(), List.of(), runtimeRepository, ProcessMetrics.noop());

    ProcessJobContext context = newContext();
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, List.of());
    when(runtimeRepository.loadPipelineSteps(any())).thenReturn(List.of());

    List<ProcessStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.PIPELINE_STEP_MISSING.name());
  }

  @Test
  void execute_failsFastWithPluginNotFound_whenComputeImplCodeNotRegistered() {
    // P2-5:COMPUTE step 显式配了 impl_code "ghostPlugin"(非默认 sentinel),但 plugin 注册表里没有
    // → PrepareStep 应直接返回 PROCESS_COMPUTE_PLUGIN_NOT_FOUND failure,后续 stage 全部跳过。
    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(), List.of(), runtimeRepository, ProcessMetrics.noop());

    ProcessJobContext context = newContext();
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, fullPipelineWith("ghostPlugin"));

    List<ProcessStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).stage()).isEqualTo(ProcessStage.PREPARE);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo("PROCESS_COMPUTE_PLUGIN_NOT_FOUND");
    assertThat(results.get(0).message()).contains("ghostPlugin");
    assertThat(context.getResolvedPlugin()).isNull();
    assertThat(context.getAttributes())
        .containsEntry(ProcessRuntimeKeys.PROCESS_PLUGIN_NOT_FOUND, "ghostPlugin");
  }

  @Test
  void execute_failsWithStepNotFound_whenStageBeanMissingForConfiguredStage() {
    // 故意构造缺 PREPARE bean 的 step 列表(模拟 Spring DI 漏注册)
    List<ProcessStageStep> incomplete = new ArrayList<>();
    for (ProcessStage stage : ProcessStage.values()) {
      if (stage != ProcessStage.PREPARE) {
        incomplete.add(realStep(stage));
      }
    }
    // executor 构造期会因 buildDefaultStepDefinitions 缺 PREPARE bean 抛 IllegalStateException
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new DefaultProcessStageExecutor(
                    incomplete, List.of(), runtimeRepository, ProcessMetrics.noop()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing process step bean for stage");
  }

  // ─── 辅助 ────────────────────────────────────────────────────────────────────

  private ProcessJobContext newContext() {
    ProcessJobContext context = new ProcessJobContext();
    context.setTenantId("t1");
    context.setWorkerId("w1");
    context.setJobCode("JOB_PROCESS");
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    context.getAttributes().put(PipelineRuntimeKeys.TASK_ID, 42L);
    return context;
  }

  private List<PipelineStepDefinition> fullPipelineWith(String computeImplCode) {
    List<PipelineStepDefinition> steps = new ArrayList<>();
    int order = 1;
    for (ProcessStage stage :
        List.of(
            ProcessStage.PREPARE,
            ProcessStage.COMPUTE,
            ProcessStage.VALIDATE,
            ProcessStage.COMMIT,
            ProcessStage.FEEDBACK)) {
      String implCode = stage == ProcessStage.COMPUTE ? computeImplCode : "PROCESS_" + stage.name();
      steps.add(
          new PipelineStepDefinition(
              (long) order,
              1L,
              "PROCESS_" + stage.name(),
              "Process " + stage.name(),
              stage.name(),
              order++,
              implCode,
              Map.of(),
              0,
              "NONE",
              0,
              true));
    }
    return steps;
  }

  private List<ProcessStageStep> allStageStepBeans() {
    List<ProcessStageStep> steps = new ArrayList<>();
    for (ProcessStage stage : ProcessStage.values()) {
      steps.add(realStep(stage));
    }
    return steps;
  }

  /** 用真实的 stage step 实现(都委托到 plugin 或 noop)代替 mock,保持新 5-stage 行为可观察。 */
  private ProcessStageStep realStep(ProcessStage stage) {
    return switch (stage) {
      case PREPARE -> new PrepareStep();
      case COMPUTE -> new ComputeStep();
      case VALIDATE -> new ValidateStep();
      case COMMIT -> new CommitStep();
      case FEEDBACK -> new FeedbackStep();
    };
  }
}
