package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.constants.BatchFileConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.utils.PrivateTempFiles;
import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
import io.github.pinpols.batch.worker.core.support.StageFailureCode;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStage;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import io.github.pinpols.batch.worker.imports.stage.support.ImportStageSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * V6-D-5: DefaultImportStageExecutor 5 路径单测 — success / business-error / infra-error /
 * step-not-found / pipeline-step-missing。和 DefaultExportStageExecutorTest 5 单测保持对称。
 */
// LENIENT 保留:setUp() 在所有用例之前预置了 startStepRun 以及
// stubStep() 内部对 stage()/implCode()/stepCode()/stepName() 的共享 stub,
// 但 STEP_NOT_FOUND / PIPELINE_STEP_MISSING 等用例不会触发其中部分调用,严格模式会误报 UnnecessaryStubbing。
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("导入阶段执行器单测:成功,业务异常,基础设施异常与步骤缺失的结果归类")
class DefaultImportStageExecutorTest {

  @Mock
  private PlatformPipelineRunRepository pipelineRuns;

  @Mock
  private ImportRecordGovernanceService recordGovernanceService;

  private ImportStageStep receiveStep;
  private DefaultImportStageExecutor executor;
  private SimpleMeterRegistry meterRegistry;

  private static final Long PIPELINE_INSTANCE_ID = 100L;
  private static final Long STEP_RUN_ID = 200L;

  @BeforeEach
  void setUp() {
    receiveStep = stubStep(ImportStage.RECEIVE);
    meterRegistry = new SimpleMeterRegistry();

    when(pipelineRuns.startStepRun(any(), any(), any(), any())).thenReturn(STEP_RUN_ID);

    List<ImportStageStep> allSteps = new ArrayList<>();
    allSteps.add(receiveStep);
    for (ImportStage stage : ImportStage.values()) {
      if (stage != ImportStage.RECEIVE) {
        allSteps.add(stubStep(stage));
      }
    }
    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
    when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);
    executor = new DefaultImportStageExecutor(
        allSteps, pipelineRuns, recordGovernanceService, meterRegistryProvider);
  }

  @Test
  @DisplayName("回放能力按执行器实际 RECEIVE 和 LOAD 阶段解析冻结插件配置")
  @SuppressWarnings("unchecked")
  void replayCapability_resolvesTemplateFromReceiveAndSelectedLoadStep() {
    ReceiveStep configuredReceiveStep = mock(ReceiveStep.class);
    when(configuredReceiveStep.stage()).thenReturn(ImportStage.RECEIVE);
    when(configuredReceiveStep.implCode()).thenReturn("IMPORT_RECEIVE");
    when(configuredReceiveStep.stepCode()).thenReturn("IMPORT_RECEIVE");
    when(configuredReceiveStep.stepName()).thenReturn("Receive");
    LoadStep configuredLoadStep = mock(LoadStep.class);
    when(configuredLoadStep.stage()).thenReturn(ImportStage.LOAD);
    when(configuredLoadStep.implCode()).thenReturn("IMPORT_LOAD");
    when(configuredLoadStep.stepCode()).thenReturn("IMPORT_LOAD");
    when(configuredLoadStep.stepName()).thenReturn("Load");
    Map<String, Object> frozenTemplateConfig = Map.of("load_target_ref", "custom-jdbc");
    when(configuredReceiveStep.resolveTemplateConfig(any())).thenReturn(frozenTemplateConfig);
    ReplayCapabilityDeclaration expected = ReplayCapabilityDeclaration.unknown("custom-jdbc");
    when(configuredLoadStep.replayCapability(frozenTemplateConfig)).thenReturn(expected);
    List<ImportStageStep> configuredSteps = new ArrayList<>();
    configuredSteps.add(configuredReceiveStep);
    for (ImportStage stage : ImportStage.values()) {
      if (stage != ImportStage.RECEIVE && stage != ImportStage.LOAD) {
        configuredSteps.add(stubStep(stage));
      }
    }
    configuredSteps.add(configuredLoadStep);
    ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
    when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);
    DefaultImportStageExecutor replayExecutor = new DefaultImportStageExecutor(
        configuredSteps, pipelineRuns, recordGovernanceService, meterRegistryProvider);
    ImportJobContext context = buildContext();

    ReplayCapabilityDeclaration actual = replayExecutor.resolveReplayCapability(context);

    assertThat(actual).isEqualTo(expected);
    verify(configuredReceiveStep).resolveTemplateConfig(context);
    verify(configuredLoadStep).replayCapability(frozenTemplateConfig);
  }

  @Test
  @DisplayName("步骤成功时返回成功结果,并收尾错误输出且登记步骤运行成功")
  void execute_returnsSuccess_whenStepSucceeds() {
    when(receiveStep.execute(any())).thenReturn(ImportStageResult.success(ImportStage.RECEIVE));
    ImportJobContext context = buildContext();
    context.getAttributes().put(PipelineRuntimeKeys.IMPORT_PARSED_COUNT, 10L);
    context.getAttributes().put(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT, 9L);
    context.getAttributes().put(PipelineRuntimeKeys.IMPORT_LOADED_COUNT, 8L);
    context.getAttributes().put(PipelineRuntimeKeys.IMPORT_SKIPPED_COUNT, 1L);

    List<ImportStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isTrue();
    assertThat(results.get(0).stage()).isEqualTo(ImportStage.RECEIVE);
    verify(pipelineRuns).finishStepRunSuccess(eq(STEP_RUN_ID), any());
    verify(recordGovernanceService).finalizeErrorOutput(context);
    assertThat(meterRegistry
            .counter("import.file.rows.total", "workerType", "IMPORT", "phase", "parsed")
            .count())
        .isEqualTo(10.0);
    assertThat(meterRegistry
            .counter("import.file.rows.total", "workerType", "IMPORT", "phase", "loaded")
            .count())
        .isEqualTo(8.0);
  }

  @Test
  @DisplayName("错误产物收尾异常仅记录净化摘要,不改变已成功的阶段结果")
  void execute_masksCredentialInFinalizeFailure(CapturedOutput output) {
    when(receiveStep.execute(any())).thenReturn(ImportStageResult.success(ImportStage.RECEIVE));
    Mockito.doThrow(new IllegalStateException("finalize token=probe-secret failed"))
        .when(recordGovernanceService)
        .finalizeErrorOutput(any());

    List<ImportStageResult> results = executor.execute(buildContext());

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().success()).isTrue();
    assertThat(output.getOut()).doesNotContain("probe-secret");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("导入运行中保护超龄文件,成功收尾删除,失败收尾解锁并保留取证文件")
  void shouldProtectActiveFilesAndCleanByOutcome_whenImportExecutionFinishes(
      boolean success, @TempDir Path tempDirectory) throws Exception {
    String originalTempRoot = System.getProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY);
    System.setProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY, tempDirectory.toString());
    List<Path> stagingFiles = new ArrayList<>();
    Instant cutoff = Instant.now().minus(Duration.ofHours(6));
    try {
      when(receiveStep.execute(any())).thenAnswer(invocation -> {
        ImportJobContext context = invocation.getArgument(0);
        stagingFiles.add(ImportStageSupport.createStagingFile(context, "parsed"));
        stagingFiles.add(ImportStageSupport.createStagingFile(context, "parsed-p1"));
        stagingFiles.add(ImportStageSupport.createValidatedFile(context));
        for (Path file : stagingFiles) {
          Files.writeString(file, "retained import evidence");
          Files.setLastModifiedTime(file, FileTime.from(cutoff.minus(Duration.ofHours(2))));
        }

        assertThat(PrivateTempFiles.deleteStaleUnlockedFiles(
                BatchFileConstants.IMPORT_STAGE_PREFIX, cutoff))
            .isZero();
        for (Path file : stagingFiles) {
          assertThat(Files.readString(file)).isEqualTo("retained import evidence");
        }
        return success
            ? ImportStageResult.success(ImportStage.RECEIVE)
            : ImportStageResult.failure(ImportStage.RECEIVE, "TEST_FAILURE", "expected failure");
      });

      List<ImportStageResult> results = executor.execute(buildContext());

      assertThat(results.get(0).success()).isEqualTo(success);
      for (Path file : stagingFiles) {
        if (success) {
          assertThat(file).doesNotExist();
        } else {
          assertThat(file).exists();
        }
      }
      assertThat(PrivateTempFiles.deleteStaleUnlockedFiles(
              BatchFileConstants.IMPORT_STAGE_PREFIX, cutoff))
          .isEqualTo(success ? 0 : 3);
      try (var remaining = Files.list(stagingFiles.get(0).getParent())) {
        assertThat(remaining).isEmpty();
      }
    } finally {
      if (originalTempRoot == null) {
        System.clearProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY);
      } else {
        System.setProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY, originalTempRoot);
      }
    }
  }

  @Test
  @DisplayName("步骤抛业务异常时归类为业务错误,错误码与明细一并记账")
  void execute_returnsBusinessError_whenStepThrowsBizException() {
    when(receiveStep.execute(any()))
        .thenThrow(BizException.of(
            ResultCode.INVALID_ARGUMENT, "error.import.receive_failed", "channel unreachable"));
    ImportJobContext context = buildContext();

    List<ImportStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.BUSINESS_ERROR.name());
    assertThat(results.get(0).errorKey()).isEqualTo("error.import.receive_failed");
    verify(pipelineRuns)
        .finishStepRunFailure(
            eq(STEP_RUN_ID),
            eq(StageFailureCode.BUSINESS_ERROR.name()),
            eq("error.import.receive_failed"),
            eq("error.import.receive_failed"),
            eq("[\"channel unreachable\"]"),
            any());
  }

  @Test
  @DisplayName("步骤抛运行时异常时归类为基础设施错误,且不保存异常中的凭据")
  void execute_returnsInfraError_whenStepThrowsRuntimeException(CapturedOutput output) {
    when(receiveStep.execute(any()))
        .thenThrow(new RuntimeException("sftp token=probe-secret connection refused"));
    ImportJobContext context = buildContext();

    List<ImportStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.INFRA_ERROR.name());
    assertThat(results.get(0).message())
        .contains("connection refused")
        .doesNotContain("probe-secret");
    assertThat(results.get(0).errorArgs()).doesNotContain("probe-secret");
    assertThat(output.getOut()).doesNotContain("probe-secret");
    verify(pipelineRuns).finishStepRunFailure(eq(STEP_RUN_ID), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("实现编码未注册时返回步骤未找到结果,不执行任何阶段")
  void execute_returnsStepNotFound_whenImplCodeNotRegistered() {
    ImportJobContext context = buildContext("UNKNOWN_IMPL");

    List<ImportStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.STEP_NOT_FOUND.name());
  }

  @Test
  @DisplayName("管道未配置任何步骤时返回步骤缺失结果")
  void execute_returnsPipelineStepMissing_whenNoStepsConfigured() {
    ImportJobContext context = new ImportJobContext();
    context.setTenantId("t1");
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, List.of());

    List<ImportStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.PIPELINE_STEP_MISSING.name());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private ImportJobContext buildContext() {
    return buildContext("IMPORT_RECEIVE");
  }

  private ImportJobContext buildContext(String implCode) {
    ImportJobContext context = new ImportJobContext();
    context.setTenantId("t1");
    context.setWorkerId("w1");
    context.setJobCode("JOB_001");
    PipelineStepDefinition step = new PipelineStepDefinition(
        1L,
        "IMPORT_RECEIVE",
        "Import Receive",
        ImportStage.RECEIVE.name(),
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

  private static ImportStageStep stubStep(ImportStage stage) {
    ImportStageStep step = mock(ImportStageStep.class);
    String code = "IMPORT_" + stage.name();
    when(step.stage()).thenReturn(stage);
    when(step.implCode()).thenReturn(code);
    when(step.stepCode()).thenReturn(code);
    when(step.stepName()).thenReturn(code);
    return step;
  }
}
