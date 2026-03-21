package com.example.batch.worker.exports.stage;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
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

    private final List<ExportStageStep> steps;
    private final PlatformFileRuntimeRepository runtimeRepository;

    public DefaultExportStageExecutor(List<ExportStageStep> steps,
                                      PlatformFileRuntimeRepository runtimeRepository) {
        this.steps = steps;
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public List<ExportStageResult> execute(ExportJobContext context) {
        List<ExportStageResult> results = new ArrayList<>();
        Long pipelineInstanceId = runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
        String lastSuccessStage = stringValue(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE));
        for (ExportStage stage : orderedStages()) {
            runtimeRepository.updatePipelineStage(pipelineInstanceId, stage.name(), lastSuccessStage);
            Long stepRunId = runtimeRepository.startStepRun(
                    pipelineInstanceId,
                    stage.name(),
                    stage.name(),
                    buildInputSummary(context, stage)
            );
            ExportStageResult result = steps.stream()
                    .filter(step -> step.stage() == stage)
                    .findFirst()
                    .map(step -> step.execute(context))
                    .orElse(ExportStageResult.failure(stage, "EXPORT_STEP_MISSING", "step not found"));
            results.add(result);
            if (result.success()) {
                lastSuccessStage = stage.name();
                context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE, lastSuccessStage);
                runtimeRepository.finishStepRunSuccess(stepRunId, buildOutputSummary(context, result));
            } else {
                runtimeRepository.finishStepRunFailure(stepRunId, result.code(), result.message(), buildOutputSummary(context, result));
            }
            if (!result.success()) {
                break;
            }
        }
        return results;
    }

    @Override
    public List<ExportStage> orderedStages() {
        return List.of(
                ExportStage.PREPARE,
                ExportStage.GENERATE,
                ExportStage.STORE,
                ExportStage.REGISTER,
                ExportStage.COMPLETE
        );
    }

    private Map<String, Object> buildInputSummary(ExportJobContext context, ExportStage stage) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stage", stage.name());
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
        summary.put("fileId", context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        summary.put("recordCount", context.getAttributes().get("recordCount"));
        summary.put("fileSizeBytes", context.getAttributes().get("fileSizeBytes"));
        summary.put("objectName", context.getAttributes().get("objectName"));
        return summary;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
