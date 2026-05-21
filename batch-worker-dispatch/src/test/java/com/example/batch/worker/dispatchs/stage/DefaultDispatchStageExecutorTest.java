package com.example.batch.worker.dispatchs.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.StageFailureCode;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

/**
 * Unit tests for DefaultDispatchStageExecutor — verifies three stage exit paths: - success: step
 * returns DispatchStageResult.success - business error: step throws BizException → BUSINESS_ERROR
 * code, does not propagate - infra error: step throws RuntimeException → INFRA_ERROR code, does not
 * propagate
 */
// LENIENT 保留:setUp() 在所有用例之前预置了 runtimeRepository.toLong / startStepRun 以及
// stubStep() 内部对 stage()/implCode()/stepCode()/stepName() 的共享 stub,
// 但 STEP_NOT_FOUND / PIPELINE_STEP_MISSING 等用例不会触发其中部分调用,严格模式会误报 UnnecessaryStubbing。
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultDispatchStageExecutorTest {

  @Mock private PlatformFileRuntimeRepository runtimeRepository;

  // The step under test
  private DispatchStageStep prepareStep;

  private DefaultDispatchStageExecutor executor;

  private static final Long PIPELINE_INSTANCE_ID = 100L;
  private static final Long STEP_RUN_ID = 200L;

  @BeforeEach
  void setUp() {
    prepareStep = stubStep(DispatchStage.PREPARE);

    when(runtimeRepository.toLong(any())).thenReturn(PIPELINE_INSTANCE_ID);
    when(runtimeRepository.startStepRun(any(), any(), any(), any())).thenReturn(STEP_RUN_ID);

    // Provide all required stages so buildDefaultStepDefinitions() does not throw
    List<DispatchStageStep> allSteps = new ArrayList<>();
    allSteps.add(prepareStep);
    for (DispatchStage stage : DispatchStage.values()) {
      if (stage != DispatchStage.PREPARE) {
        allSteps.add(stubStep(stage));
      }
    }
    executor =
        new DefaultDispatchStageExecutor(allSteps, runtimeRepository, new SimpleMeterRegistry());
  }

  @Test
  void execute_returnsSuccess_whenStepSucceeds() {
    when(prepareStep.execute(any())).thenReturn(DispatchStageResult.success(DispatchStage.PREPARE));
    DispatchJobContext context = buildContext();

    List<DispatchStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isTrue();
    assertThat(results.get(0).stage()).isEqualTo(DispatchStage.PREPARE);
    verify(runtimeRepository).finishStepRunSuccess(eq(STEP_RUN_ID), any());
  }

  @Test
  void execute_returnsBusinessError_whenStepThrowsBizException() {
    when(prepareStep.execute(any()))
        .thenThrow(
            BizException.of(
                ResultCode.INVALID_ARGUMENT,
                "error.common.invalid_argument",
                "invalid dispatch config"));
    DispatchJobContext context = buildContext();

    List<DispatchStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.BUSINESS_ERROR.name());
    assertThat(results.get(0).message()).isEqualTo("error.common.invalid_argument");
    assertThat(results.get(0).errorKey()).isEqualTo("error.common.invalid_argument");
    verify(runtimeRepository)
        .finishStepRunFailure(
            eq(STEP_RUN_ID),
            eq(StageFailureCode.BUSINESS_ERROR.name()),
            eq("error.common.invalid_argument"),
            eq("error.common.invalid_argument"),
            eq("[\"invalid dispatch config\"]"),
            any());
  }

  @Test
  void execute_returnsInfraError_whenStepThrowsRuntimeException() {
    when(prepareStep.execute(any())).thenThrow(new RuntimeException("upstream connection refused"));
    DispatchJobContext context = buildContext();

    List<DispatchStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.INFRA_ERROR.name());
    assertThat(results.get(0).message()).isEqualTo("upstream connection refused");
    verify(runtimeRepository)
        .finishStepRunFailure(eq(STEP_RUN_ID), any(), any(), any(), any(), any());
  }

  @Test
  void execute_returnsStepNotFound_whenImplCodeNotRegistered() {
    DispatchJobContext context = buildContext("UNKNOWN_IMPL");

    List<DispatchStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.STEP_NOT_FOUND.name());
  }

  @Test
  void execute_returnsPipelineStepMissing_whenNoStepsConfigured() {
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, List.of());
    when(runtimeRepository.loadPipelineSteps(any())).thenReturn(List.of());

    List<DispatchStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.PIPELINE_STEP_MISSING.name());
  }

  private DispatchJobContext buildContext() {
    return buildContext("DISPATCH_PREPARE");
  }

  private DispatchJobContext buildContext(String implCode) {
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.setWorkerId("w1");
    context.setDispatchId("d1");
    PipelineStepDefinition step =
        new PipelineStepDefinition(
            1L,
            1L,
            "DISPATCH_PREPARE",
            "Dispatch Prepare",
            DispatchStage.PREPARE.name(),
            1,
            implCode,
            Map.of(),
            0,
            "NONE",
            0,
            true);
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, List.of(step));
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    return context;
  }

  private static DispatchStageStep stubStep(DispatchStage stage) {
    DispatchStageStep step = mock(DispatchStageStep.class);
    String code = "DISPATCH_" + stage.name();
    when(step.stage()).thenReturn(stage);
    when(step.implCode()).thenReturn(code);
    when(step.stepCode()).thenReturn(code);
    when(step.stepName()).thenReturn(code);
    return step;
  }
}
