package io.github.pinpols.batch.worker.processes.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.worker.core.config.WorkerCheckpointProperties;
import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.core.support.StageFailureCode;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import io.github.pinpols.batch.worker.processes.metrics.ProcessMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

// LENIENT 保留:setUp() 预置了 runtimeRepository.toLong / startStepRun 共享 stub,
// 但 defaultStepDefinitions / 缺 PREPARE bean 直接抛错等用例并不触达这些 stub,严格模式会误报 UnnecessaryStubbing。
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultProcessStageExecutorTest {

  @Mock private PlatformFileRuntimeRepository runtimeRepository;

  private static final Long PIPELINE_INSTANCE_ID = 100L;
  private static final Long STEP_RUN_ID = 200L;

  @BeforeEach
  void setUp() {
    // 真实转换而非恒返 PIPELINE_INSTANCE_ID:多分区守卫要用 toLong 读 PARTITION_COUNT,
    // 恒返 100 会让守卫把任何 attribute 都当 partitionCount=100 误降级。
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> realToLong(inv.getArgument(0)));
    when(runtimeRepository.startStepRun(any(), any(), any(), any())).thenReturn(STEP_RUN_ID);
  }

  private static Long realToLong(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String string && !string.isBlank()) {
      return Long.valueOf(string);
    }
    return null;
  }

  @Test
  void defaultStepDefinitions_useWapBookendsOrder() {
    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(),
            List.of(),
            runtimeRepository,
            ProcessMetrics.noop(),
            disabledStageSkip());

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
            allStageStepBeans(),
            List.of(plugin),
            runtimeRepository,
            ProcessMetrics.noop(),
            disabledStageSkip());

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
            allStageStepBeans(),
            List.of(plugin),
            runtimeRepository,
            ProcessMetrics.noop(),
            disabledStageSkip());

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
    willThrow(
            BizException.of(
                ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument", "bad spec"))
        .given(plugin)
        .prepare(any());

    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(),
            List.of(plugin),
            runtimeRepository,
            ProcessMetrics.noop(),
            disabledStageSkip());

    ProcessJobContext context = newContext();
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, fullPipelineWith("p1"));

    List<ProcessStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.BUSINESS_ERROR.name());
    assertThat(results.get(0).message()).isEqualTo("error.common.invalid_argument");
    assertThat(results.get(0).errorKey()).isEqualTo("error.common.invalid_argument");
    assertThat(results.get(0).errorArgs()).isEqualTo("[\"bad spec\"]");
    verify(runtimeRepository)
        .finishStepRunFailure(
            eq(STEP_RUN_ID),
            eq(StageFailureCode.BUSINESS_ERROR.name()),
            eq("error.common.invalid_argument"),
            eq("error.common.invalid_argument"),
            eq("[\"bad spec\"]"),
            any());
  }

  @Test
  void execute_returnsInfraError_whenPluginPrepareThrowsRuntimeException() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.implCode()).thenReturn("p1");
    willThrow(new RuntimeException("io fail")).given(plugin).prepare(any());

    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(),
            List.of(plugin),
            runtimeRepository,
            ProcessMetrics.noop(),
            disabledStageSkip());

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
            allStageStepBeans(),
            List.of(plugin),
            runtimeRepository,
            ProcessMetrics.noop(),
            disabledStageSkip());

    ProcessJobContext context = newContext();
    // COMPUTE step impl_code 是默认 sentinel(PROCESS_COMPUTE),走 payload 注入的 processImplCode 回退
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
            allStageStepBeans(),
            List.of(),
            runtimeRepository,
            ProcessMetrics.noop(),
            disabledStageSkip());

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
            allStageStepBeans(),
            List.of(),
            runtimeRepository,
            ProcessMetrics.noop(),
            disabledStageSkip());

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
            allStageStepBeans(),
            List.of(),
            runtimeRepository,
            ProcessMetrics.noop(),
            disabledStageSkip());

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
                    incomplete,
                    List.of(),
                    runtimeRepository,
                    ProcessMetrics.noop(),
                    disabledStageSkip()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing process step bean for stage");
  }

  // ─── P1 阶段级续跑(stage-skip)────────────────────────────────────────────────

  @Test
  void stageSkip_skipsPriorSucceededComputeAndValidate_stillRunsCommitAndFeedback() {
    // 上一 attempt COMPUTE + VALIDATE 已成功(pipeline_step_run 有 SUCCESS 记录),COMMIT 前崩溃重派。
    // 开关开:跳过 COMPUTE/VALIDATE(不重算),COMMIT/FEEDBACK 恒跑(原子发布决策每次重做)。
    when(runtimeRepository.loadSucceededStepCodes(PIPELINE_INSTANCE_ID))
        .thenReturn(Set.of("PROCESS_COMPUTE", "PROCESS_VALIDATE"));
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.implCode()).thenReturn("dailySummary");

    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(),
            List.of(plugin),
            runtimeRepository,
            ProcessMetrics.noop(),
            enabledStageSkip());

    ProcessJobContext context = newContext();
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, fullPipelineWith("dailySummary"));

    List<ProcessStageResult> results = executor.execute(context);

    // 跳过的 stage 不进 results;只剩 PREPARE(未声明跳过)+ COMMIT + FEEDBACK。
    assertThat(results)
        .extracting(ProcessStageResult::stage)
        .containsExactly(ProcessStage.PREPARE, ProcessStage.COMMIT, ProcessStage.FEEDBACK);
    assertThat(results).allMatch(ProcessStageResult::success);
    verify(plugin).prepare(context);
    verify(plugin, never()).compute(any()); // 跳过,不重算
    verify(plugin, never()).validate(any()); // 跳过
    verify(plugin).commit(context); // 恒跑:原子发布决策
    verify(plugin).feedback(context); // 恒跑:清 staging / 推水位
  }

  @Test
  void stageSkip_carriesForwardWatermarkFromPriorSuccessOutputSummary() {
    // P1-1:跳过 COMPUTE 时,须从上次 SUCCESS 的 output_summary 回灌 highWaterMarkOut / processedCount 到
    // attributes,否则 report 水位 null → 下周期 INCREMENTAL 重读重发。
    when(runtimeRepository.loadSucceededStepCodes(PIPELINE_INSTANCE_ID))
        .thenReturn(Set.of("PROCESS_COMPUTE", "PROCESS_VALIDATE"));
    when(runtimeRepository.loadLatestSucceededStepOutputSummary(
            PIPELINE_INSTANCE_ID, "PROCESS_COMPUTE"))
        .thenReturn(Map.of("highWaterMarkOut", "20260708120000", "processedCount", 42));
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.implCode()).thenReturn("dailySummary");

    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(),
            List.of(plugin),
            runtimeRepository,
            ProcessMetrics.noop(),
            enabledStageSkip());

    ProcessJobContext context = newContext();
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, fullPipelineWith("dailySummary"));

    executor.execute(context);

    // COMPUTE 被跳过,但其上次成功产出的水位/计数已回灌 attributes(供 report 正常推水位)。
    assertThat(context.getAttributes().get(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT))
        .isEqualTo("20260708120000");
    assertThat(context.getAttributes().get("processedCount")).isEqualTo(42);
    verify(plugin, never()).compute(any());
  }

  @Test
  void stageSkip_runsFullPipeline_whenNoPriorSuccess() {
    // 首次运行(无历史 SUCCESS 记录):即使开关开,也全量跑(回归保护)。
    when(runtimeRepository.loadSucceededStepCodes(PIPELINE_INSTANCE_ID)).thenReturn(Set.of());
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.implCode()).thenReturn("dailySummary");
    when(plugin.compute(any())).thenReturn(ProcessStageResult.success(ProcessStage.COMPUTE));

    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(),
            List.of(plugin),
            runtimeRepository,
            ProcessMetrics.noop(),
            enabledStageSkip());

    ProcessJobContext context = newContext();
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, fullPipelineWith("dailySummary"));

    List<ProcessStageResult> results = executor.execute(context);

    assertThat(results).hasSize(5);
    verify(plugin).compute(context);
    verify(plugin).validate(context);
  }

  @Test
  void stageSkip_disabled_runsFullPipeline_evenWithPriorSuccess() {
    // 开关关(本 PR 默认):即使有历史 SUCCESS 记录,也从首 stage 全量重跑(彻底回归保护)。
    // 关开关时甚至不查历史记录 —— 验证 loadSucceededStepCodes 从不被调用。
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.implCode()).thenReturn("dailySummary");
    when(plugin.compute(any())).thenReturn(ProcessStageResult.success(ProcessStage.COMPUTE));

    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(),
            List.of(plugin),
            runtimeRepository,
            ProcessMetrics.noop(),
            disabledStageSkip());

    ProcessJobContext context = newContext();
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, fullPipelineWith("dailySummary"));

    List<ProcessStageResult> results = executor.execute(context);

    assertThat(results).hasSize(5);
    verify(plugin).compute(context);
    verify(plugin).validate(context);
    verify(runtimeRepository, never()).loadSucceededStepCodes(any());
  }

  @Test
  void stageSkip_neverSkipsCommit_evenIfPriorCommitSucceeded() {
    // COMMIT 不在 skip-safe 集:即便历史上 COMMIT 也成功过(极端场景),重派仍重跑 COMMIT
    // (原子发布幂等:staging 已清则发布 0 行),绝不因"曾成功"跳过发布决策。
    when(runtimeRepository.loadSucceededStepCodes(PIPELINE_INSTANCE_ID))
        .thenReturn(Set.of("PROCESS_COMPUTE", "PROCESS_VALIDATE", "PROCESS_COMMIT"));
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.implCode()).thenReturn("dailySummary");

    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(),
            List.of(plugin),
            runtimeRepository,
            ProcessMetrics.noop(),
            enabledStageSkip());

    ProcessJobContext context = newContext();
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, fullPipelineWith("dailySummary"));

    List<ProcessStageResult> results = executor.execute(context);

    assertThat(results)
        .extracting(ProcessStageResult::stage)
        .containsExactly(ProcessStage.PREPARE, ProcessStage.COMMIT, ProcessStage.FEEDBACK);
    verify(plugin).commit(context); // COMMIT 恒跑
  }

  @Test
  void stageSkip_degradedByMultiPartition_siblingSuccessDoesNotCauseSkip() {
    // Critical 守卫(与 P0 LoadStep.checkpointDegradedByMultiPartition 对称):
    // partitionCount=2 时 K 个 partition task 共享同一 pipeline_instance,但 staging 副作用是
    // task 级(process-<taskId>)。兄弟 partition 的 COMPUTE SUCCESS step_run 不得让本 task 跳过
    // COMPUTE(否则本 task 的 staging 从未生成 → COMMIT 读 0 行静默少发布)。
    // 期望:多分区整体降级为不跳 —— 全量跑,且从不查历史成功记录。
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.implCode()).thenReturn("dailySummary");
    when(plugin.compute(any())).thenReturn(ProcessStageResult.success(ProcessStage.COMPUTE));

    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(),
            List.of(plugin),
            runtimeRepository,
            ProcessMetrics.noop(),
            enabledStageSkip());

    ProcessJobContext context = newContext();
    context.getAttributes().put(PipelineRuntimeKeys.PARTITION_COUNT, 2);
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, fullPipelineWith("dailySummary"));

    List<ProcessStageResult> results = executor.execute(context);

    assertThat(results).hasSize(5); // 全量跑,零跳过
    verify(plugin).compute(context); // COMPUTE 未被兄弟 SUCCESS 误跳
    verify(plugin).validate(context);
    verify(runtimeRepository, never()).loadSucceededStepCodes(any()); // 降级后甚至不查历史
  }

  @Test
  void stageSkip_singlePartition_partitionCountOne_stillSkips() {
    // 边界:partitionCount=1(显式单分区)不触发降级,跳过逻辑照常生效。
    when(runtimeRepository.loadSucceededStepCodes(PIPELINE_INSTANCE_ID))
        .thenReturn(Set.of("PROCESS_COMPUTE", "PROCESS_VALIDATE"));
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.implCode()).thenReturn("dailySummary");

    DefaultProcessStageExecutor executor =
        new DefaultProcessStageExecutor(
            allStageStepBeans(),
            List.of(plugin),
            runtimeRepository,
            ProcessMetrics.noop(),
            enabledStageSkip());

    ProcessJobContext context = newContext();
    context.getAttributes().put(PipelineRuntimeKeys.PARTITION_COUNT, 1);
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, fullPipelineWith("dailySummary"));

    List<ProcessStageResult> results = executor.execute(context);

    assertThat(results)
        .extracting(ProcessStageResult::stage)
        .containsExactly(ProcessStage.PREPARE, ProcessStage.COMMIT, ProcessStage.FEEDBACK);
    verify(plugin, never()).compute(any());
  }

  private WorkerCheckpointProperties disabledStageSkip() {
    return new WorkerCheckpointProperties(); // stageSkip.enabled 默认 false
  }

  private WorkerCheckpointProperties enabledStageSkip() {
    WorkerCheckpointProperties properties = new WorkerCheckpointProperties();
    properties.getStageSkip().setEnabled(true);
    return properties;
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
      PipelineStepDefinition step =
          PipelineStepDefinition.builder()
              .id((long) order)
              .pipelineDefinitionId(1L)
              .stepCode("PROCESS_" + stage.name())
              .stepName("Process " + stage.name())
              .stageCode(stage.name())
              .stepOrder(order++)
              .implCode(implCode)
              .stepParams(Map.of())
              .timeoutSeconds(0)
              .retryPolicy("NONE")
              .retryMaxCount(0)
              .enabled(true)
              .build();
      steps.add(step);
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
      case FEEDBACK -> new FeedbackStep(ProcessMetrics.noop());
    };
  }
}
