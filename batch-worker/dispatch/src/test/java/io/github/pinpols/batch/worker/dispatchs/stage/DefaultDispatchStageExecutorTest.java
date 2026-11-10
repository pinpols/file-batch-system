package io.github.pinpols.batch.worker.dispatchs.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
import io.github.pinpols.batch.worker.core.support.StageFailureCode;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchSecretBlockedException;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchSecretResolver;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelGateway;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * DefaultDispatchStageExecutor 单元测试，校验三个阶段的退出路径：成功时步骤
 * returns DispatchStageResult.success - business error: step throws BizException → BUSINESS_ERROR
 * 错误码不会向外传播；基础设施错误时，步骤抛出 RuntimeException 并返回 INFRA_ERROR，且不会
 * propagate
 */
// LENIENT 保留:setUp() 在所有用例之前预置了 startStepRun 以及
// stubStep() 内部对 stage()/implCode()/stepCode()/stepName() 的共享 stub,
// 但 STEP_NOT_FOUND / PIPELINE_STEP_MISSING 等用例不会触发其中部分调用,严格模式会误报 UnnecessaryStubbing。
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("分发阶段执行器:成功、业务异常、基础设施异常与两类步骤缺失的出口路径及失败码映射")
class DefaultDispatchStageExecutorTest {

  @Mock
  private PlatformPipelineRunRepository pipelineRuns;

  // 当前被测步骤。
  private DispatchStageStep prepareStep;

  private DefaultDispatchStageExecutor executor;
  private SimpleMeterRegistry meterRegistry;

  private static final Long PIPELINE_INSTANCE_ID = 100L;
  private static final Long STEP_RUN_ID = 200L;

  @BeforeEach
  void setUp() {
    prepareStep = stubStep(DispatchStage.PREPARE);

    when(pipelineRuns.startStepRun(any(), any(), any(), any())).thenReturn(STEP_RUN_ID);

    // 提供全部必需阶段，避免 buildDefaultStepDefinitions() 抛出异常。
    List<DispatchStageStep> allSteps = new ArrayList<>();
    allSteps.add(prepareStep);
    for (DispatchStage stage : DispatchStage.values()) {
      if (stage != DispatchStage.PREPARE) {
        allSteps.add(stubStep(stage));
      }
    }
    meterRegistry = new SimpleMeterRegistry();
    executor = new DefaultDispatchStageExecutor(allSteps, pipelineRuns, meterRegistry);
  }

  @Test
  @DisplayName("步骤成功时执行成功:结果含准备阶段,回执指标不带租户标签,并标记步骤运行成功")
  void execute_returnsSuccess_whenStepSucceeds() {
    when(prepareStep.execute(any())).thenReturn(DispatchStageResult.success(DispatchStage.PREPARE));
    DispatchJobContext context = buildContext();

    List<DispatchStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isTrue();
    assertThat(results.get(0).stage()).isEqualTo(DispatchStage.PREPARE);
    assertThat(meterRegistry
            .find("dispatch.receipt.total")
            .tag("workerType", "DISPATCH")
            .counter())
        .isNotNull();
    assertThat(hasTagKey("dispatch.receipt.total", "tenant")).isFalse();
    verify(pipelineRuns).finishStepRunSuccess(eq(STEP_RUN_ID), any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DISPATCH_SECRET_REVOKED_OR_EXPIRED",
        "EXTERNAL_OUTCOME_UNKNOWN",
        "DISPATCH_RETRY_FAILED"
      })
  @DisplayName("密钥撤销或结果不明时终止,已知投递失败仍进入默认补偿分支")
  void execute_stopsBeforeCompensationForBlockedOrUnknownRetry(String failureCode) {
    FileDispatchRepository repository = mock(FileDispatchRepository.class);
    DispatchChannelGateway gateway = mock(DispatchChannelGateway.class);
    DispatchSecretResolver resolver = mock(DispatchSecretResolver.class);
    DispatchStageStep compensation = stubStep(DispatchStage.COMPENSATE);
    RetryDispatchStep retry = new RetryDispatchStep(repository, gateway, resolver);
    List<DispatchStageStep> steps = new ArrayList<>();
    for (DispatchStage stage : DispatchStage.values()) {
      steps.add(
          stage == DispatchStage.RETRY
              ? retry
              : stage == DispatchStage.COMPENSATE ? compensation : stubStep(stage));
    }
    DefaultDispatchStageExecutor stageExecutor =
        new DefaultDispatchStageExecutor(steps, pipelineRuns, meterRegistry);
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, Map.of("id", 10L));
    context.getAttributes().put(PipelineRuntimeKeys.CHANNEL_CONFIG, Map.of("channel_type", "API"));
    context
        .getAttributes()
        .put(
            DispatchRuntimeKeys.DISPATCH_PAYLOAD,
            new DispatchPayload("10", null, "CH1", "target", null, null, null, null, null, null));
    context.getAttributes().put(DispatchRuntimeKeys.RETRY_REQUESTED, true);
    PipelineStepDefinition retryDefinition = new PipelineStepDefinition(
        1L,
        "DISPATCH_RETRY",
        "Retry",
        "RETRY",
        1,
        "DISPATCH_RETRY",
        Map.of("onFailureNextStageCode", "COMPENSATE"),
        0,
        "NONE",
        0,
        true);
    PipelineStepDefinition compensateDefinition = new PipelineStepDefinition(
        2L,
        "DISPATCH_COMPENSATE",
        "Compensate",
        "COMPENSATE",
        2,
        "DISPATCH_COMPENSATE",
        Map.of("terminalOnSuccess", true),
        0,
        "NONE",
        0,
        true);
    context
        .getAttributes()
        .put(
            PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS,
            List.of(retryDefinition, compensateDefinition));
    when(compensation.execute(any()))
        .thenReturn(DispatchStageResult.success(DispatchStage.COMPENSATE));
    if ("DISPATCH_SECRET_REVOKED_OR_EXPIRED".equals(failureCode)) {
      when(resolver.resolveInto(any(), any(), any(), any()))
          .thenThrow(new DispatchSecretBlockedException(failureCode));
    } else {
      when(resolver.resolveInto(any(), any(), any(), any())).thenReturn(Set.of());
      when(repository.beginAttempt("t1", 10L, "CH1")).thenReturn(1);
      if ("EXTERNAL_OUTCOME_UNKNOWN".equals(failureCode)) {
        when(gateway.dispatch(any()))
            .thenReturn(DispatchResult.externalOutcomeUnknown("ext", null, "response lost"));
      } else {
        when(gateway.dispatch(any())).thenReturn(DispatchResult.failureWithoutReceipt("failed"));
      }
      when(repository.markAttemptFailed(any(), any(), any(), any(), any())).thenReturn(1);
    }

    List<DispatchStageResult> results = stageExecutor.execute(context);

    if ("DISPATCH_RETRY_FAILED".equals(failureCode)) {
      assertThat(results)
          .extracting(DispatchStageResult::code)
          .containsExactly(failureCode, "SUCCESS");
      verify(compensation, times(1)).execute(any());
    } else {
      assertThat(results).extracting(DispatchStageResult::code).containsExactly(failureCode);
      verify(compensation, never()).execute(any());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("重试后确认成功才计入成功回执,确认失败不能计入")
  void execute_countsReceiptOnlyWhenRetryRecoveryFullySucceeds(boolean ackSucceeds) {
    DispatchStageStep delivery = stubStep(DispatchStage.DISPATCH);
    DispatchStageStep retry = stubStep(DispatchStage.RETRY);
    DispatchStageStep ack = stubStep(DispatchStage.ACK);
    DispatchStageStep complete = stubStep(DispatchStage.COMPLETE);
    List<DispatchStageStep> steps = new ArrayList<>();
    for (DispatchStage stage : DispatchStage.values()) {
      steps.add(
          switch (stage) {
            case DISPATCH -> delivery;
            case RETRY -> retry;
            case ACK -> ack;
            case COMPLETE -> complete;
            default -> stubStep(stage);
          });
    }
    DefaultDispatchStageExecutor stageExecutor =
        new DefaultDispatchStageExecutor(steps, pipelineRuns, meterRegistry);
    when(delivery.execute(any()))
        .thenReturn(DispatchStageResult.failure(
            DispatchStage.DISPATCH, "DISPATCH_SEND_FAILED", "temporary"));
    when(retry.execute(any())).thenAnswer(invocation -> {
      DispatchJobContext context = invocation.getArgument(0);
      context.getAttributes().put(DispatchRuntimeKeys.RETRY_RECOVERED, true);
      return DispatchStageResult.success(DispatchStage.RETRY);
    });
    when(ack.execute(any()))
        .thenReturn(
            ackSucceeds
                ? DispatchStageResult.success(DispatchStage.ACK)
                : DispatchStageResult.failure(
                    DispatchStage.ACK, "DISPATCH_ACK_FAILED", "ack failed"));
    when(complete.execute(any())).thenReturn(DispatchStageResult.success(DispatchStage.COMPLETE));
    PipelineStepDefinition dispatchDefinition = new PipelineStepDefinition(
        1L,
        "DISPATCH_DISPATCH",
        "Dispatch",
        "DISPATCH",
        1,
        "DISPATCH_DISPATCH",
        Map.of("onFailureNextStageCode", "RETRY"),
        0,
        "NONE",
        0,
        true);
    PipelineStepDefinition retryDefinition = new PipelineStepDefinition(
        2L,
        "DISPATCH_RETRY",
        "Retry",
        "RETRY",
        2,
        "DISPATCH_RETRY",
        Map.of("onSuccessNextStageCode", "ACK"),
        0,
        "NONE",
        0,
        true);
    PipelineStepDefinition ackDefinition = new PipelineStepDefinition(
        3L,
        "DISPATCH_ACK",
        "Ack",
        "ACK",
        3,
        "DISPATCH_ACK",
        Map.of("onSuccessNextStageCode", "COMPLETE"),
        0,
        "NONE",
        0,
        true);
    PipelineStepDefinition completeDefinition = new PipelineStepDefinition(
        4L,
        "DISPATCH_COMPLETE",
        "Complete",
        "COMPLETE",
        4,
        "DISPATCH_COMPLETE",
        Map.of("terminalOnSuccess", true),
        0,
        "NONE",
        0,
        true);
    List<PipelineStepDefinition> definitions =
        List.of(dispatchDefinition, retryDefinition, ackDefinition, completeDefinition);
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, definitions);

    stageExecutor.execute(context);

    var receiptCounter = meterRegistry.find("dispatch.receipt.total").counter();
    if (ackSucceeds) {
      assertThat(receiptCounter).isNotNull();
      assertThat(receiptCounter.count()).isEqualTo(1.0);
    } else {
      assertThat(receiptCounter).isNull();
    }
  }

  @Test
  @DisplayName("步骤抛业务异常时不向上传播,结果映射为业务错误码并回带错误键与参数摘要")
  void execute_returnsBusinessError_whenStepThrowsBizException() {
    when(prepareStep.execute(any()))
        .thenThrow(BizException.of(
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
    verify(pipelineRuns)
        .finishStepRunFailure(
            eq(STEP_RUN_ID),
            eq(StageFailureCode.BUSINESS_ERROR.name()),
            eq("error.common.invalid_argument"),
            eq("error.common.invalid_argument"),
            eq("[\"invalid dispatch config\"]"),
            any());
  }

  @Test
  @DisplayName("步骤抛运行时异常时不向上传播,结果映射为基础设施错误码并回带异常信息")
  void execute_returnsInfraError_whenStepThrowsRuntimeException() {
    when(prepareStep.execute(any())).thenThrow(new RuntimeException("upstream connection refused"));
    DispatchJobContext context = buildContext();

    List<DispatchStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.INFRA_ERROR.name());
    assertThat(results.get(0).message()).contains("upstream connection refused");
    verify(pipelineRuns).finishStepRunFailure(eq(STEP_RUN_ID), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("基础设施异常的阶段结果和日志不保存凭据")
  void execute_masksCredentialInInfraFailure(CapturedOutput output) {
    when(prepareStep.execute(any()))
        .thenThrow(new RuntimeException("upstream token=probe-secret refused"));

    List<DispatchStageResult> results = executor.execute(buildContext());

    DispatchStageResult result = results.getFirst();
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo(StageFailureCode.INFRA_ERROR.name());
    assertThat(result.message()).doesNotContain("probe-secret");
    assertThat(result.errorArgs()).doesNotContain("probe-secret");
    assertThat(output.getOut()).doesNotContain("probe-secret");
  }

  @Test
  @DisplayName("流水线定义的实现码没有注册任何步骤时,结果映射为步骤未找到")
  void execute_returnsStepNotFound_whenImplCodeNotRegistered() {
    DispatchJobContext context = buildContext("UNKNOWN_IMPL");

    List<DispatchStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.STEP_NOT_FOUND.name());
  }

  @Test
  @DisplayName("上下文与库中都没有流水线步骤定义时,结果映射为流水线步骤缺失")
  void execute_returnsPipelineStepMissing_whenNoStepsConfigured() {
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, List.of());

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
    PipelineStepDefinition step = new PipelineStepDefinition(
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

  private boolean hasTagKey(String meterName, String tagKey) {
    return meterRegistry.getMeters().stream()
        .filter(meter -> meterName.equals(meter.getId().getName()))
        .flatMap(meter -> meter.getId().getTags().stream())
        .anyMatch(tag -> tagKey.equals(tag.getKey()));
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
