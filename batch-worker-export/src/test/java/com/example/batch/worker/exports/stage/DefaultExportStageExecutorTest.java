package com.example.batch.worker.exports.stage;

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
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
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
 * Unit tests for DefaultExportStageExecutor — verifies three stage exit paths:
 * - success: step returns ExportStageResult.success
 * - business error: step throws BizException → BUSINESS_ERROR code, does not propagate
 * - infra error: step throws RuntimeException → INFRA_ERROR code, does not propagate
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultExportStageExecutorTest {

    @Mock
    private PlatformFileRuntimeRepository runtimeRepository;

    // The step under test
    private ExportStageStep prepareStep;

    private DefaultExportStageExecutor executor;

    private static final Long PIPELINE_INSTANCE_ID = 100L;
    private static final Long STEP_RUN_ID = 200L;

    @BeforeEach
    void setUp() {
        prepareStep = stubStep(ExportStage.PREPARE);

        when(runtimeRepository.toLong(any())).thenReturn(PIPELINE_INSTANCE_ID);
        when(runtimeRepository.startStepRun(any(), any(), any(), any())).thenReturn(STEP_RUN_ID);

        // Provide all required stages so buildDefaultStepDefinitions() does not throw
        List<ExportStageStep> allSteps = new ArrayList<>();
        allSteps.add(prepareStep);
        for (ExportStage stage : ExportStage.values()) {
            if (stage != ExportStage.PREPARE) {
                allSteps.add(stubStep(stage));
            }
        }
        executor = new DefaultExportStageExecutor(allSteps, runtimeRepository);
    }

    @Test
    void execute_returnsSuccess_whenStepSucceeds() {
        when(prepareStep.execute(any())).thenReturn(ExportStageResult.success(ExportStage.PREPARE));
        ExportJobContext context = buildContext();

        List<ExportStageResult> results = executor.execute(context);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(0).stage()).isEqualTo(ExportStage.PREPARE);
        verify(runtimeRepository).finishStepRunSuccess(eq(STEP_RUN_ID), any());
    }

    @Test
    void execute_returnsBusinessError_whenStepThrowsBizException() {
        when(prepareStep.execute(any()))
                .thenThrow(new BizException(ResultCode.INVALID_ARGUMENT, "invalid export config"));
        ExportJobContext context = buildContext();

        List<ExportStageResult> results = executor.execute(context);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).code()).isEqualTo(StageFailureCode.BUSINESS_ERROR.name());
        assertThat(results.get(0).message()).isEqualTo("invalid export config");
        verify(runtimeRepository).finishStepRunFailure(eq(STEP_RUN_ID), any(), any(), any());
    }

    @Test
    void execute_returnsInfraError_whenStepThrowsRuntimeException() {
        when(prepareStep.execute(any()))
                .thenThrow(new RuntimeException("connection timeout"));
        ExportJobContext context = buildContext();

        List<ExportStageResult> results = executor.execute(context);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).code()).isEqualTo(StageFailureCode.INFRA_ERROR.name());
        assertThat(results.get(0).message()).isEqualTo("connection timeout");
        verify(runtimeRepository).finishStepRunFailure(eq(STEP_RUN_ID), any(), any(), any());
    }

    @Test
    void execute_returnsStepNotFound_whenImplCodeNotRegistered() {
        ExportJobContext context = buildContext("UNKNOWN_IMPL");

        List<ExportStageResult> results = executor.execute(context);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).code()).isEqualTo(StageFailureCode.STEP_NOT_FOUND.name());
    }

    @Test
    void execute_returnsPipelineStepMissing_whenNoStepsConfigured() {
        ExportJobContext context = new ExportJobContext();
        context.setTenantId("t1");
        context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, List.of());
        when(runtimeRepository.loadPipelineSteps(any())).thenReturn(List.of());

        List<ExportStageResult> results = executor.execute(context);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).code()).isEqualTo(StageFailureCode.PIPELINE_STEP_MISSING.name());
    }

    private ExportJobContext buildContext() {
        return buildContext("EXPORT_PREPARE");
    }

    private ExportJobContext buildContext(String implCode) {
        ExportJobContext context = new ExportJobContext();
        context.setTenantId("t1");
        context.setWorkerId("w1");
        context.setJobCode("JOB_001");
        PipelineStepDefinition step = new PipelineStepDefinition(
                1L, 1L, "EXPORT_PREPARE", "Export Prepare", ExportStage.PREPARE.name(),
                1, implCode, Map.of(), 0, "NONE", 0, true
        );
        context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, List.of(step));
        context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
        return context;
    }

    private static ExportStageStep stubStep(ExportStage stage) {
        ExportStageStep step = mock(ExportStageStep.class);
        String code = "EXPORT_" + stage.name();
        when(step.stage()).thenReturn(stage);
        when(step.implCode()).thenReturn(code);
        when(step.stepCode()).thenReturn(code);
        when(step.stepName()).thenReturn(code);
        return step;
    }
}
