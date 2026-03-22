package com.example.batch.worker.dispatchs.stage;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.PipelineStepFlowSupport;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DefaultDispatchStageExecutor implements DispatchStageExecutor {

    private final Map<String, DispatchStageStep> stepsByImplCode;
    private final Map<DispatchStage, DispatchStageStep> stepsByStage;
    private final List<PipelineStepTemplate> defaultStepDefinitions;
    private final PlatformFileRuntimeRepository runtimeRepository;

    public DefaultDispatchStageExecutor(List<DispatchStageStep> steps,
                                        PlatformFileRuntimeRepository runtimeRepository) {
        this.stepsByImplCode = indexByImplCode(steps);
        this.stepsByStage = indexByStage(steps);
        this.defaultStepDefinitions = buildDefaultStepDefinitions();
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public List<DispatchStageResult> execute(DispatchJobContext context) {
        List<PipelineStepDefinition> configuredSteps = configuredSteps(context);
        List<DispatchStageResult> results = new ArrayList<>();
        if (configuredSteps.isEmpty()) {
            results.add(DispatchStageResult.failure(DispatchStage.PREPARE, "DISPATCH_PIPELINE_STEP_MISSING", "pipeline step definition missing"));
            return results;
        }
        int guard = PipelineStepFlowSupport.maxTransitionGuard(configuredSteps);
        PipelineStepDefinition currentStep = PipelineStepFlowSupport.firstStep(configuredSteps);
        while (currentStep != null) {
            if (guard-- <= 0) {
                throw new BizException(ResultCode.STATE_CONFLICT, "dispatch pipeline step flow contains a cycle");
            }
            DispatchStageResult result = executeStep(context, currentStep, results);
            currentStep = PipelineStepFlowSupport.resolveNextStep(currentStep, result.success(), configuredSteps, context.getAttributes());
            if (!result.success() && currentStep == null) {
                break;
            }
        }
        return results;
    }

    private DispatchStageResult executeStep(DispatchJobContext context,
                                             PipelineStepDefinition stepDefinition,
                                             List<DispatchStageResult> results) {
        DispatchStage stage = toStage(stepDefinition.stageCode());
        Long pipelineInstanceId = runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
        String lastSuccessStage = stringValue(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE));
        runtimeRepository.updatePipelineStage(pipelineInstanceId, stepDefinition.stageCode(), lastSuccessStage);
        Long stepRunId = runtimeRepository.startStepRun(
                pipelineInstanceId,
                stepDefinition.stepCode(),
                stepDefinition.stageCode(),
                buildInputSummary(context, stepDefinition)
        );
        DispatchStageStep step = stepsByImplCode.get(stepDefinition.implCode());
        DispatchStageResult result = step == null
                ? DispatchStageResult.failure(stage, "DISPATCH_STEP_MISSING", "step impl not found: " + stepDefinition.implCode())
                : step.execute(context);
        results.add(result);
        if (result.success()) {
            context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE, stepDefinition.stageCode());
            runtimeRepository.finishStepRunSuccess(stepRunId, buildOutputSummary(context, result));
        } else {
            runtimeRepository.finishStepRunFailure(stepRunId, result.code(), result.message(), buildOutputSummary(context, result));
        }
        return result;
    }

    private Map<String, Object> buildInputSummary(DispatchJobContext context, PipelineStepDefinition stepDefinition) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stepCode", stepDefinition.stepCode());
        summary.put("stage", stepDefinition.stageCode());
        summary.put("implCode", stepDefinition.implCode());
        summary.put("tenantId", context.getTenantId());
        summary.put("fileId", context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        summary.put("dispatchId", context.getDispatchId());
        summary.put("workerId", context.getWorkerId());
        return summary;
    }

    private Map<String, Object> buildOutputSummary(DispatchJobContext context, DispatchStageResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("success", result.success());
        summary.put("code", result.code());
        summary.put("message", result.message());
        summary.put("stage", result.stage().name());
        summary.put("fileId", context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        summary.put("receiptStatus", context.getAttributes().get("receiptStatus"));
        summary.put("externalRequestId", context.getAttributes().get("externalRequestId"));
        summary.put("receiptCode", context.getAttributes().get("receiptCode"));
        return summary;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public List<PipelineStepTemplate> defaultStepDefinitions() {
        return defaultStepDefinitions;
    }

    private List<PipelineStepDefinition> configuredSteps(DispatchJobContext context) {
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

    private DispatchStage toStage(String stageCode) {
        try {
            return DispatchStage.valueOf(stageCode);
        } catch (IllegalArgumentException exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "unsupported dispatch stage code: " + stageCode, exception);
        }
    }

    private Map<String, DispatchStageStep> indexByImplCode(List<DispatchStageStep> steps) {
        Map<String, DispatchStageStep> indexed = new LinkedHashMap<>();
        for (DispatchStageStep step : steps) {
            register(indexed, step.implCode(), step);
        }
        return Map.copyOf(indexed);
    }

    private Map<DispatchStage, DispatchStageStep> indexByStage(List<DispatchStageStep> steps) {
        Map<DispatchStage, DispatchStageStep> indexed = new LinkedHashMap<>();
        for (DispatchStageStep step : steps) {
            if (indexed.putIfAbsent(step.stage(), step) != null) {
                throw new IllegalStateException("duplicate dispatch stage registered: " + step.stage().name());
            }
        }
        return Map.copyOf(indexed);
    }

    private List<PipelineStepTemplate> buildDefaultStepDefinitions() {
        List<PipelineStepTemplate> templates = new ArrayList<>();
        int order = 1;
        for (DispatchStage stage : List.of(
                DispatchStage.PREPARE,
                DispatchStage.DISPATCH,
                DispatchStage.ACK,
                DispatchStage.RETRY,
                DispatchStage.COMPENSATE,
                DispatchStage.COMPLETE
        )) {
            DispatchStageStep step = stepsByStage.get(stage);
            if (step == null) {
                throw new IllegalStateException("missing dispatch step bean for stage: " + stage.name());
            }
            Map<String, Object> stepParams = switch (stage) {
                case ACK -> Map.of("onSuccessNextStageCode", DispatchStage.COMPLETE.name());
                case COMPLETE, COMPENSATE -> Map.of("terminalOnSuccess", Boolean.TRUE);
                case RETRY -> Map.of("onFailureNextStageCode", DispatchStage.COMPENSATE.name());
                default -> Map.of();
            };
            templates.add(new PipelineStepTemplate(
                    step.stepCode(),
                    step.stepName(),
                    stage.name(),
                    order++,
                    step.implCode(),
                    stepParams,
                    0,
                    "NONE",
                    0,
                    true
            ));
        }
        return List.copyOf(templates);
    }

    private void register(Map<String, DispatchStageStep> indexed, String implCode, DispatchStageStep step) {
        if (!indexed.containsKey(implCode)) {
            indexed.put(implCode, step);
            return;
        }
        throw new IllegalStateException("duplicate dispatch step implCode registered: " + implCode);
    }
}
