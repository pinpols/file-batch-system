package com.example.batch.worker.dispatchs.stage;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
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

    private final List<DispatchStageStep> steps;
    private final PlatformFileRuntimeRepository runtimeRepository;

    public DefaultDispatchStageExecutor(List<DispatchStageStep> steps,
                                        PlatformFileRuntimeRepository runtimeRepository) {
        this.steps = steps;
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public List<DispatchStageResult> execute(DispatchJobContext context) {
        List<DispatchStageResult> results = new ArrayList<>();
        DispatchStageResult result = executeStage(context, DispatchStage.PREPARE, results);
        if (!result.success()) {
            return results;
        }
        result = executeStage(context, DispatchStage.DISPATCH, results);
        if (!result.success()) {
            runFailureStages(context, result, results);
            return results;
        }
        result = executeStage(context, DispatchStage.ACK, results);
        if (!result.success()) {
            runFailureStages(context, result, results);
            return results;
        }
        executeStage(context, DispatchStage.COMPLETE, results);
        return results;
    }

    private void runFailureStages(DispatchJobContext context,
                                  DispatchStageResult failedResult,
                                  List<DispatchStageResult> results) {
        boolean retryRequested = Boolean.TRUE.equals(context.getAttributes().get("retryRequested"));
        if (retryRequested) {
            DispatchStageResult retryResult = executeStage(context, DispatchStage.RETRY, results);
            if (retryResult.success() && Boolean.TRUE.equals(context.getAttributes().get("retryRecovered"))) {
                DispatchStageResult ackResult = executeStage(context, DispatchStage.ACK, results);
                if (ackResult.success()) {
                    executeStage(context, DispatchStage.COMPLETE, results);
                    return;
                }
                failedResult = ackResult;
            } else {
                failedResult = retryResult;
            }
        }
        if (!failedResult.success()) {
            executeStage(context, DispatchStage.COMPENSATE, results);
        }
    }

    private DispatchStageResult executeStage(DispatchJobContext context,
                                             DispatchStage stage,
                                             List<DispatchStageResult> results) {
        Long pipelineInstanceId = runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
        String lastSuccessStage = stringValue(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE));
        runtimeRepository.updatePipelineStage(pipelineInstanceId, stage.name(), lastSuccessStage);
        Long stepRunId = runtimeRepository.startStepRun(
                pipelineInstanceId,
                stage.name(),
                stage.name(),
                buildInputSummary(context, stage)
        );
        DispatchStageResult result = steps.stream()
                .filter(step -> step.stage() == stage)
                .findFirst()
                .map(step -> step.execute(context))
                .orElse(DispatchStageResult.failure(stage, "DISPATCH_STEP_MISSING", "step not found"));
        results.add(result);
        if (result.success()) {
            context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE, stage.name());
            runtimeRepository.finishStepRunSuccess(stepRunId, buildOutputSummary(context, result));
        } else {
            runtimeRepository.finishStepRunFailure(stepRunId, result.code(), result.message(), buildOutputSummary(context, result));
        }
        return result;
    }

    private Map<String, Object> buildInputSummary(DispatchJobContext context, DispatchStage stage) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stage", stage.name());
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
    public List<DispatchStage> orderedStages() {
        return List.of(
                DispatchStage.PREPARE,
                DispatchStage.DISPATCH,
                DispatchStage.ACK,
                DispatchStage.RETRY,
                DispatchStage.COMPENSATE,
                DispatchStage.COMPLETE
        );
    }
}
