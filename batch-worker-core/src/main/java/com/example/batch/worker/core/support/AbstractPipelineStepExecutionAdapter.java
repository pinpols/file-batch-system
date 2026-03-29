package com.example.batch.worker.core.support;

import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * Pipeline 生命周期统一在 core 中维护，业务 worker 只关心上下文构造和阶段结果解释。
 */
public abstract class AbstractPipelineStepExecutionAdapter<C, R> implements StepExecutionAdapter {

    private final PlatformFileRuntimeRepository runtimeRepository;

    protected AbstractPipelineStepExecutionAdapter(PlatformFileRuntimeRepository runtimeRepository) {
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public final StepExecutionResponse execute(StepExecutionRequest request) {
        Map<String, Object> attributes = new LinkedHashMap<>(request.context() == null ? Map.of() : request.context());
        String traceId = resolveTraceId(attributes);
        injectMdc(request, attributes, traceId);
        try {
            return doExecute(request, attributes, traceId);
        } finally {
            BatchMdc.remove(StructuredLogField.TENANT_ID);
            BatchMdc.remove(StructuredLogField.TRACE_ID);
            BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
            BatchMdc.remove(StructuredLogField.WORKER_ID);
            BatchMdc.remove(StructuredLogField.RUN_MODE);
        }
    }

    private void injectMdc(StepExecutionRequest request, Map<String, Object> attributes, String traceId) {
        BatchMdc.putIfAbsent(StructuredLogField.TENANT_ID, request.tenantId());
        BatchMdc.putIfAbsent(StructuredLogField.TRACE_ID, traceId);
        Object jobInstanceId = attributes.get(PipelineRuntimeKeys.JOB_INSTANCE_ID);
        if (jobInstanceId != null) {
            BatchMdc.putIfAbsent(StructuredLogField.JOB_INSTANCE_ID, String.valueOf(jobInstanceId));
        }
        BatchMdc.putIfAbsent(StructuredLogField.WORKER_ID, request.workerId());
        String runMode = resolveText(attributes, PipelineRuntimeKeys.RUN_MODE, PipelineRuntimeKeys.LEGACY_RUN_MODE);
        BatchMdc.putIfAbsent(StructuredLogField.RUN_MODE, runMode);
    }

    private StepExecutionResponse doExecute(StepExecutionRequest request, Map<String, Object> attributes, String traceId) {
        String jobCode = resolveJobCode(request, attributes);
        Long fileId = runtimeRepository.toLong(attributes.get(PipelineRuntimeKeys.FILE_ID));
        Long pipelineDefinitionId = runtimeRepository.ensurePipelineDefinition(
                request.tenantId(),
                jobCode,
                pipelineType(),
                pipelineWorkerGroup(),
                pipelineDescription(),
                defaultPipelineSteps()
        );
        if (pipelineDefinitionId == null) {
            return new StepExecutionResponse(false, "PIPELINE_DEFINITION_MISSING", "pipeline definition missing");
        }
        List<PipelineStepDefinition> pipelineSteps = runtimeRepository.loadPipelineSteps(pipelineDefinitionId);
        if (pipelineSteps.isEmpty()) {
            return new StepExecutionResponse(false, "PIPELINE_STEP_DEFINITION_MISSING", "pipeline step definition missing");
        }
        Long pipelineInstanceId = runtimeRepository.createPipelineInstance(
                request.tenantId(),
                pipelineDefinitionId,
                jobCode,
                pipelineType(),
                fileId,
                runtimeRepository.toLong(attributes.get(PipelineRuntimeKeys.JOB_INSTANCE_ID)),
                resolveInitialStage(pipelineSteps),
                traceId
        );
        attributes.put(PipelineRuntimeKeys.TRACE_ID, traceId);
        attributes.put(PipelineRuntimeKeys.JOB_CODE, jobCode);
        attributes.put(PipelineRuntimeKeys.PIPELINE_DEFINITION_ID, pipelineDefinitionId);
        attributes.put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, pipelineInstanceId);
        attributes.put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, pipelineSteps);
        attributes.putIfAbsent(PipelineRuntimeKeys.JOB_CODE, request.jobCode());
        attributes.putIfAbsent("stepCode", request.stepCode());
        attributes.putIfAbsent(PipelineRuntimeKeys.FILE_ID, fileId);
        if (fileId != null) {
            attributes.put(PipelineRuntimeKeys.FILE_RECORD, runtimeRepository.loadFileRecord(request.tenantId(), fileId));
        }
        try {
            C context = buildContext(request, attributes, fileId);
            List<R> results = executeStages(context);
            R failed = firstFailure(results);
            if (failed == null) {
                String successStage = lastSuccessfulStage(attributes);
                runtimeRepository.markPipelineSuccess(
                        pipelineInstanceId,
                        successStage,
                        successStage
                );
                return buildSuccessResponse(context, results, attributes);
            }
            String failureStage = resultStage(failed);
            runtimeRepository.markPipelineFailed(
                    pipelineInstanceId,
                    failureStage,
                    lastSuccessfulStage(attributes)
            );
            handlePipelineFailure(attributes, resultCode(failed), resultMessage(failed));
            return new StepExecutionResponse(false, resultCode(failed), resultMessage(failed));
        } catch (Exception exception) {
            runtimeRepository.markPipelineFailed(
                    pipelineInstanceId,
                    initialStage(),
                    lastSuccessfulStage(attributes)
            );
            handlePipelineFailure(attributes, unexpectedErrorCode(), exception.getMessage());
            return new StepExecutionResponse(false, unexpectedErrorCode(), exception.getMessage());
        }
    }

    protected PlatformFileRuntimeRepository runtimeRepository() {
        return runtimeRepository;
    }

    protected String pipelineWorkerGroup() {
        return "worker-" + pipelineType().toLowerCase();
    }

    protected String resolveInitialStage(List<PipelineStepDefinition> pipelineSteps) {
        PipelineStepDefinition firstStep = PipelineStepFlowSupport.firstStep(pipelineSteps);
        return firstStep == null ? initialStage() : firstStep.stageCode();
    }

    protected String unexpectedErrorCode() {
        return pipelineType() + "_PIPELINE_ERROR";
    }

    protected String resolveTraceId(Map<String, Object> attributes) {
        String traceId = resolveText(attributes, PipelineRuntimeKeys.TRACE_ID, "sourceTraceId");
        return StringUtils.hasText(traceId) ? traceId : pipelineType().toLowerCase() + "-" + UUID.randomUUID();
    }

    protected String resolveJobCode(StepExecutionRequest request, Map<String, Object> attributes) {
        String jobCode = resolveText(attributes, PipelineRuntimeKeys.JOB_CODE, PipelineRuntimeKeys.PIPELINE_CODE, "jobCode", "pipelineCode");
        if (StringUtils.hasText(jobCode)) {
            return jobCode;
        }
        if (request != null && StringUtils.hasText(request.jobCode())) {
            return request.jobCode();
        }
        if (request != null && StringUtils.hasText(request.stepCode())) {
            return request.stepCode();
        }
        return pipelineType();
    }

    protected String resolveText(Map<String, Object> attributes, String... keys) {
        for (String key : keys) {
            Object value = attributes.get(key);
            if (value instanceof String text && StringUtils.hasText(text)) {
                return text;
            }
            if (value != null) {
                String text = String.valueOf(value);
                if (StringUtils.hasText(text) && !"null".equalsIgnoreCase(text)) {
                    return text;
                }
            }
        }
        return null;
    }

    private String lastSuccessfulStage(Map<String, Object> attributes) {
        Object lastSuccessStage = attributes.get(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE);
        return lastSuccessStage == null ? initialStage() : String.valueOf(lastSuccessStage);
    }

    private R firstFailure(List<R> results) {
        if (results == null) {
            return null;
        }
        return results.stream()
                .filter(result -> !isSuccess(result))
                .findFirst()
                .orElse(null);
    }

    protected abstract String pipelineType();

    protected abstract String pipelineDescription();

    protected abstract List<PipelineStepTemplate> defaultPipelineSteps();

    protected abstract String initialStage();

    protected abstract C buildContext(StepExecutionRequest request,
                                      Map<String, Object> attributes,
                                      Long fileId) throws Exception;

    protected abstract List<R> executeStages(C context);

    protected abstract boolean isSuccess(R result);

    protected abstract String resultStage(R result);

    protected abstract String resultCode(R result);

    protected abstract String resultMessage(R result);

    protected abstract StepExecutionResponse buildSuccessResponse(C context,
                                                                 List<R> results,
                                                                 Map<String, Object> attributes);

    protected abstract void handlePipelineFailure(Map<String, Object> attributes,
                                                  String errorCode,
                                                  String errorMessage);
}
