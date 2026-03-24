package com.example.batch.worker.imports.stage;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.PipelineStepFlowSupport;
import com.example.batch.worker.core.support.StageFailureCode;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DefaultImportStageExecutor implements ImportStageExecutor {

    private final Map<String, ImportStageStep> stepsByImplCode;
    private final Map<ImportStage, ImportStageStep> stepsByStage;
    private final List<PipelineStepTemplate> defaultStepDefinitions;
    private final PlatformFileRuntimeRepository runtimeRepository;
    private final ImportRecordGovernanceService recordGovernanceService;

    public DefaultImportStageExecutor(List<ImportStageStep> steps,
                                      PlatformFileRuntimeRepository runtimeRepository,
                                      ImportRecordGovernanceService recordGovernanceService) {
        this.stepsByImplCode = indexByImplCode(steps);
        this.stepsByStage = indexByStage(steps);
        this.defaultStepDefinitions = buildDefaultStepDefinitions();
        this.runtimeRepository = runtimeRepository;
        this.recordGovernanceService = recordGovernanceService;
    }

    @Override
    public List<ImportStageResult> execute(ImportJobContext context) {
        List<PipelineStepDefinition> configuredSteps = configuredSteps(context);
        List<ImportStageResult> results = new ArrayList<>();
        try {
            if (configuredSteps.isEmpty()) {
                results.add(ImportStageResult.failure(ImportStage.RECEIVE, StageFailureCode.PIPELINE_STEP_MISSING.name(), "pipeline step definition missing"));
                return results;
            }
            Long pipelineInstanceId = runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
            String lastSuccessStage = stringValue(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE));
            int guard = PipelineStepFlowSupport.maxTransitionGuard(configuredSteps);
            PipelineStepDefinition currentStep = PipelineStepFlowSupport.firstStep(configuredSteps);
            while (currentStep != null) {
                if (guard-- <= 0) {
                    throw new BizException(ResultCode.STATE_CONFLICT, "import pipeline step flow contains a cycle");
                }
                ImportStage stage = toStage(currentStep.stageCode());
                runtimeRepository.updatePipelineStage(pipelineInstanceId, currentStep.stageCode(), lastSuccessStage);
                Long stepRunId = runtimeRepository.startStepRun(
                        pipelineInstanceId,
                        currentStep.stepCode(),
                        currentStep.stageCode(),
                        buildInputSummary(context, currentStep)
                );
                context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_STEP_RUN_ID, stepRunId);
                ImportStageStep step = stepsByImplCode.get(currentStep.implCode());
                ImportStageResult result;
                try {
                    result = step == null
                            ? ImportStageResult.failure(stage, StageFailureCode.STEP_NOT_FOUND.name(), "step impl not found: " + currentStep.implCode())
                            : step.execute(context);
                } catch (BizException exception) {
                    log.error(
                            "import stage business error: stage={}, stepCode={}, implCode={}, tenantId={}, fileId={}",
                            stage,
                            currentStep.stepCode(),
                            currentStep.implCode(),
                            context.getTenantId(),
                            context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
                            exception);
                    result = ImportStageResult.failure(stage, StageFailureCode.BUSINESS_ERROR.name(), exception.getMessage());
                } catch (Exception exception) {
                    log.error(
                            "import stage infra error: stage={}, stepCode={}, implCode={}, tenantId={}, fileId={}",
                            stage,
                            currentStep.stepCode(),
                            currentStep.implCode(),
                            context.getTenantId(),
                            context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
                            exception);
                    result = ImportStageResult.failure(stage, StageFailureCode.INFRA_ERROR.name(), exception.getMessage());
                }
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
        } finally {
            try {
                recordGovernanceService.finalizeErrorOutput(context);
            } catch (Exception exception) {
                log.warn("failed to finalize import error output: {}", exception.getMessage(), exception);
            }
        }
    }

    @Override
    public List<PipelineStepTemplate> defaultStepDefinitions() {
        return defaultStepDefinitions;
    }

    private Map<String, Object> buildInputSummary(ImportJobContext context, PipelineStepDefinition step) {
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

    private Map<String, Object> buildOutputSummary(ImportJobContext context, ImportStageResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("success", result.success());
        summary.put("code", result.code());
        summary.put("message", result.message());
        summary.put("stage", result.stage().name());
        summary.put("fileId", context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        summary.put("parsedCount", context.getAttributes().get("parsedCount"));
        summary.put("validatedCount", context.getAttributes().get("validatedCount"));
        summary.put("loadedCount", context.getAttributes().get("loadedCount"));
        return summary;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<PipelineStepDefinition> configuredSteps(ImportJobContext context) {
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

    private ImportStage toStage(String stageCode) {
        try {
            return ImportStage.valueOf(stageCode);
        } catch (IllegalArgumentException exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "unsupported import stage code: " + stageCode, exception);
        }
    }

    private Map<String, ImportStageStep> indexByImplCode(List<ImportStageStep> steps) {
        Map<String, ImportStageStep> indexed = new LinkedHashMap<>();
        for (ImportStageStep step : steps) {
            register(indexed, step.implCode(), step);
        }
        return Map.copyOf(indexed);
    }

    private Map<ImportStage, ImportStageStep> indexByStage(List<ImportStageStep> steps) {
        Map<ImportStage, ImportStageStep> indexed = new LinkedHashMap<>();
        for (ImportStageStep step : steps) {
            if (indexed.putIfAbsent(step.stage(), step) != null) {
                throw new IllegalStateException("duplicate import stage registered: " + step.stage().name());
            }
        }
        return Map.copyOf(indexed);
    }

    private List<PipelineStepTemplate> buildDefaultStepDefinitions() {
        List<PipelineStepTemplate> templates = new ArrayList<>();
        int order = 1;
        for (ImportStage stage : List.of(
                ImportStage.RECEIVE,
                ImportStage.PREPROCESS,
                ImportStage.PARSE,
                ImportStage.VALIDATE,
                ImportStage.LOAD,
                ImportStage.FEEDBACK
        )) {
            ImportStageStep step = stepsByStage.get(stage);
            if (step == null) {
                throw new IllegalStateException("missing import step bean for stage: " + stage.name());
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

    private void register(Map<String, ImportStageStep> indexed, String implCode, ImportStageStep step) {
        if (!indexed.containsKey(implCode)) {
            indexed.put(implCode, step);
            return;
        }
        throw new IllegalStateException("duplicate import step implCode registered: " + implCode);
    }
}
