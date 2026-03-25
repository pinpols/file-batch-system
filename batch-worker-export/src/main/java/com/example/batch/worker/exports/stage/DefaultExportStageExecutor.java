package com.example.batch.worker.exports.stage;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.AbstractStageExecutor;
import com.example.batch.worker.core.support.StageFailureCode;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DefaultExportStageExecutor
        extends AbstractStageExecutor<ExportJobContext, ExportStageResult>
        implements ExportStageExecutor {

    private final Map<String, ExportStageStep> stepsByImplCode;
    private final Map<ExportStage, ExportStageStep> stepsByStage;
    private final List<PipelineStepTemplate> defaultStepDefinitions;
    private final MeterRegistry meterRegistry;

    public DefaultExportStageExecutor(List<ExportStageStep> steps,
                                      PlatformFileRuntimeRepository runtimeRepository,
                                      MeterRegistry meterRegistry) {
        super(runtimeRepository);
        this.stepsByImplCode = indexByImplCode(steps);
        this.stepsByStage = indexByStage(steps);
        this.defaultStepDefinitions = buildDefaultStepDefinitions();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public List<ExportStageResult> execute(ExportJobContext context) {
        List<ExportStageResult> results = runStageLoop(context);
        boolean overallSuccess = results.stream().allMatch(ExportStageResult::success);
        if (overallSuccess) {
            recordExportRowsMetric(context);
        }
        return results;
    }

    private void recordExportRowsMetric(ExportJobContext context) {
        Object recordCountAttr = context.getAttributes().get("recordCount");
        if (recordCountAttr instanceof Number recordCount) {
            Counter.builder("export.file.rows.total")
                    .description("Total rows written to export files")
                    .tag("tenant", context.getTenantId() != null ? context.getTenantId() : "unknown")
                    .register(meterRegistry)
                    .increment(recordCount.doubleValue());
        }
    }

    @Override
    public List<PipelineStepTemplate> defaultStepDefinitions() {
        return defaultStepDefinitions;
    }

    // ─── AbstractStageExecutor template methods ──────────────────────────────

    @Override
    protected List<PipelineStepDefinition> loadConfiguredSteps(ExportJobContext context) {
        Object definitions = context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS);
        if (definitions instanceof List<?> list) {
            List<PipelineStepDefinition> resolved = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof PipelineStepDefinition definition) {
                    resolved.add(definition);
                }
            }
            if (!resolved.isEmpty()) {
                return List.copyOf(resolved);
            }
        }
        Long pipelineDefinitionId = runtimeRepository.toLong(
                context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_DEFINITION_ID));
        return runtimeRepository.loadPipelineSteps(pipelineDefinitionId);
    }

    @Override
    protected ExportStageResult stepMissingFailure() {
        return ExportStageResult.failure(ExportStage.PREPARE,
                StageFailureCode.PIPELINE_STEP_MISSING.name(), "pipeline step definition missing");
    }

    @Override
    protected ExportStageResult executeOneStep(ExportJobContext context, PipelineStepDefinition step) {
        ExportStage stage = toStage(step.stageCode());
        ExportStageStep stageStep = stepsByImplCode.get(step.implCode());
        try {
            return stageStep == null
                    ? ExportStageResult.failure(stage, StageFailureCode.STEP_NOT_FOUND.name(),
                    "step impl not found: " + step.implCode())
                    : stageStep.execute(context);
        } catch (BizException exception) {
            log.error("export stage business error: stage={}, stepCode={}, implCode={}, tenantId={}, fileId={}",
                    stage, step.stepCode(), step.implCode(),
                    context.getTenantId(), context.getAttributes().get(PipelineRuntimeKeys.FILE_ID), exception);
            return ExportStageResult.failure(stage, StageFailureCode.BUSINESS_ERROR.name(), exception.getMessage());
        } catch (Exception exception) {
            log.error("export stage infra error: stage={}, stepCode={}, implCode={}, tenantId={}, fileId={}",
                    stage, step.stepCode(), step.implCode(),
                    context.getTenantId(), context.getAttributes().get(PipelineRuntimeKeys.FILE_ID), exception);
            return ExportStageResult.failure(stage, StageFailureCode.INFRA_ERROR.name(), exception.getMessage());
        }
    }

    @Override
    protected Map<String, Object> buildInputSummary(ExportJobContext context, PipelineStepDefinition step) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stepCode", step.stepCode());
        summary.put("stage", step.stageCode());
        summary.put("implCode", step.implCode());
        summary.put("tenantId", context.getTenantId());
        summary.put("fileId", context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        summary.put("workerId", context.getWorkerId());
        summary.put("jobCode", context.getJobCode());
        return summary;
    }

    @Override
    protected Map<String, Object> buildOutputSummary(ExportJobContext context, ExportStageResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("success", result.success());
        summary.put("code", result.code());
        summary.put("message", result.message());
        summary.put("stage", result.stage().name());
        summary.put("fileId", context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        summary.put("recordCount", context.getAttributes().get("recordCount"));
        summary.put("fileSizeBytes", context.getAttributes().get("fileSizeBytes"));
        summary.put("objectName", context.getAttributes().get("objectName"));
        return summary;
    }

    @Override
    protected String cycleDetectedMessage() {
        return "export pipeline step flow contains a cycle";
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private ExportStage toStage(String stageCode) {
        try {
            return ExportStage.valueOf(stageCode);
        } catch (IllegalArgumentException exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "unsupported export stage code: " + stageCode, exception);
        }
    }

    private Map<String, ExportStageStep> indexByImplCode(List<ExportStageStep> steps) {
        Map<String, ExportStageStep> indexed = new LinkedHashMap<>();
        for (ExportStageStep step : steps) {
            register(indexed, step.implCode(), step);
        }
        return Map.copyOf(indexed);
    }

    private Map<ExportStage, ExportStageStep> indexByStage(List<ExportStageStep> steps) {
        Map<ExportStage, ExportStageStep> indexed = new LinkedHashMap<>();
        for (ExportStageStep step : steps) {
            if (indexed.putIfAbsent(step.stage(), step) != null) {
                throw new IllegalStateException("duplicate export stage registered: " + step.stage().name());
            }
        }
        return Map.copyOf(indexed);
    }

    private List<PipelineStepTemplate> buildDefaultStepDefinitions() {
        List<PipelineStepTemplate> templates = new ArrayList<>();
        int order = 1;
        for (ExportStage stage : List.of(
                ExportStage.PREPARE,
                ExportStage.GENERATE,
                ExportStage.STORE,
                ExportStage.REGISTER,
                ExportStage.COMPLETE
        )) {
            ExportStageStep step = stepsByStage.get(stage);
            if (step == null) {
                throw new IllegalStateException("missing export step bean for stage: " + stage.name());
            }
            templates.add(new PipelineStepTemplate(
                    step.stepCode(),
                    step.stepName(),
                    stage.name(),
                    order++,
                    step.implCode(),
                    Map.of(),
                    0,
                    "NONE",
                    0,
                    true
            ));
        }
        return List.copyOf(templates);
    }

    private void register(Map<String, ExportStageStep> indexed, String implCode, ExportStageStep step) {
        if (!indexed.containsKey(implCode)) {
            indexed.put(implCode, step);
            return;
        }
        throw new IllegalStateException("duplicate export step implCode registered: " + implCode);
    }
}
