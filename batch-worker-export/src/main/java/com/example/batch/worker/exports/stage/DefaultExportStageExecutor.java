package com.example.batch.worker.exports.stage;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.PipelineStepFlowSupport;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DefaultExportStageExecutor implements ExportStageExecutor {

    private final Map<String, ExportStageStep> stepsByImplCode;
    private final Map<ExportStage, ExportStageStep> stepsByStage;
    private final List<PipelineStepTemplate> defaultStepDefinitions;
    private final PlatformFileRuntimeRepository runtimeRepository;

    public DefaultExportStageExecutor(List<ExportStageStep> steps,
                                      PlatformFileRuntimeRepository runtimeRepository) {
        this.stepsByImplCode = indexByImplCode(steps);
        this.stepsByStage = indexByStage(steps);
        this.defaultStepDefinitions = buildDefaultStepDefinitions();
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public List<ExportStageResult> execute(ExportJobContext context) {
        List<PipelineStepDefinition> configuredSteps = configuredSteps(context);
        List<ExportStageResult> results = new ArrayList<>();
        if (configuredSteps.isEmpty()) {
            results.add(ExportStageResult.failure(ExportStage.PREPARE, "EXPORT_PIPELINE_STEP_MISSING", "pipeline step definition missing"));
            return results;
        }
        Long pipelineInstanceId = runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
        String lastSuccessStage = stringValue(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE));
        int guard = PipelineStepFlowSupport.maxTransitionGuard(configuredSteps);
        PipelineStepDefinition currentStep = PipelineStepFlowSupport.firstStep(configuredSteps);
        while (currentStep != null) {
            if (guard-- <= 0) {
                throw new BizException(ResultCode.STATE_CONFLICT, "export pipeline step flow contains a cycle");
            }
            ExportStage stage = toStage(currentStep.stageCode());
            runtimeRepository.updatePipelineStage(pipelineInstanceId, currentStep.stageCode(), lastSuccessStage);
            Long stepRunId = runtimeRepository.startStepRun(
                    pipelineInstanceId,
                    currentStep.stepCode(),
                    currentStep.stageCode(),
                    buildInputSummary(context, currentStep)
            );
            ExportStageStep step = stepsByImplCode.get(currentStep.implCode());
            ExportStageResult result = step == null
                    ? ExportStageResult.failure(stage, "EXPORT_STEP_MISSING", "step impl not found: " + currentStep.implCode())
                    : step.execute(context);
            results.add(result);
            if (result.success()) {
                lastSuccessStage = currentStep.stageCode();
                context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE, lastSuccessStage);
                runtimeRepository.finishStepRunSuccess(stepRunId, buildOutputSummary(context, result));
            } else {
                runtimeRepository.finishStepRunFailure(stepRunId, result.code(), result.message(), buildOutputSummary(context, result));
            }
            currentStep = PipelineStepFlowSupport.resolveNextStep(currentStep, result.success(), configuredSteps, context.getAttributes());
            if (!result.success() && currentStep == null) {
                break;
            }
        }
        return results;
    }

    @Override
    public List<PipelineStepTemplate> defaultStepDefinitions() {
        return defaultStepDefinitions;
    }

    private Map<String, Object> buildInputSummary(ExportJobContext context, PipelineStepDefinition step) {
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

    private Map<String, Object> buildOutputSummary(ExportJobContext context, ExportStageResult result) {
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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<PipelineStepDefinition> configuredSteps(ExportJobContext context) {
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
        Long pipelineDefinitionId = runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_DEFINITION_ID));
        return runtimeRepository.loadPipelineSteps(pipelineDefinitionId);
    }

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
