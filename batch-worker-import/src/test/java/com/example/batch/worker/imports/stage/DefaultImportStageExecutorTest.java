package com.example.batch.worker.imports.stage;

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
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
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
 * V6-D-5: DefaultImportStageExecutor 5 路径单测 — success / business-error / infra-error /
 * step-not-found / pipeline-step-missing。和 DefaultExportStageExecutorTest 5 单测保持对称。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultImportStageExecutorTest {

  @Mock private PlatformFileRuntimeRepository runtimeRepository;
  @Mock private ImportRecordGovernanceService recordGovernanceService;

  private ImportStageStep receiveStep;
  private DefaultImportStageExecutor executor;

  private static final Long PIPELINE_INSTANCE_ID = 100L;
  private static final Long STEP_RUN_ID = 200L;

  @BeforeEach
  void setUp() {
    receiveStep = stubStep(ImportStage.RECEIVE);

    when(runtimeRepository.toLong(any())).thenReturn(PIPELINE_INSTANCE_ID);
    when(runtimeRepository.startStepRun(any(), any(), any(), any())).thenReturn(STEP_RUN_ID);

    List<ImportStageStep> allSteps = new ArrayList<>();
    allSteps.add(receiveStep);
    for (ImportStage stage : ImportStage.values()) {
      if (stage != ImportStage.RECEIVE) {
        allSteps.add(stubStep(stage));
      }
    }
    executor = new DefaultImportStageExecutor(allSteps, runtimeRepository, recordGovernanceService);
  }

  @Test
  void execute_returnsSuccess_whenStepSucceeds() {
    when(receiveStep.execute(any())).thenReturn(ImportStageResult.success(ImportStage.RECEIVE));
    ImportJobContext context = buildContext();

    List<ImportStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isTrue();
    assertThat(results.get(0).stage()).isEqualTo(ImportStage.RECEIVE);
    verify(runtimeRepository).finishStepRunSuccess(eq(STEP_RUN_ID), any());
    verify(recordGovernanceService).finalizeErrorOutput(context);
  }

  @Test
  void execute_returnsBusinessError_whenStepThrowsBizException() {
    when(receiveStep.execute(any()))
        .thenThrow(
            BizException.of(
                ResultCode.INVALID_ARGUMENT, "error.import.receive_failed", "channel unreachable"));
    ImportJobContext context = buildContext();

    List<ImportStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.BUSINESS_ERROR.name());
    assertThat(results.get(0).errorKey()).isEqualTo("error.import.receive_failed");
    verify(runtimeRepository)
        .finishStepRunFailure(
            eq(STEP_RUN_ID),
            eq(StageFailureCode.BUSINESS_ERROR.name()),
            eq("error.import.receive_failed"),
            eq("error.import.receive_failed"),
            eq("[\"channel unreachable\"]"),
            any());
  }

  @Test
  void execute_returnsInfraError_whenStepThrowsRuntimeException() {
    when(receiveStep.execute(any())).thenThrow(new RuntimeException("sftp connection refused"));
    ImportJobContext context = buildContext();

    List<ImportStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.INFRA_ERROR.name());
    assertThat(results.get(0).message()).isEqualTo("sftp connection refused");
    verify(runtimeRepository)
        .finishStepRunFailure(eq(STEP_RUN_ID), any(), any(), any(), any(), any());
  }

  @Test
  void execute_returnsStepNotFound_whenImplCodeNotRegistered() {
    ImportJobContext context = buildContext("UNKNOWN_IMPL");

    List<ImportStageResult> results = executor.execute(context);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).success()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(StageFailureCode.STEP_NOT_FOUND.name());
  }

  @Test
  void execute_returnsPipelineStepMissing_whenNoStepsConfigured() {
    ImportJobContext context = new ImportJobContext();
    context.setTenantId("t1");
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, List.of());
    when(runtimeRepository.loadPipelineSteps(any())).thenReturn(List.of());

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
    PipelineStepDefinition step =
        new PipelineStepDefinition(
            1L,
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
