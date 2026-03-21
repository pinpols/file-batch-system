package com.example.batch.worker.imports.stage;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DefaultImportStageExecutor implements ImportStageExecutor {

    private final List<ImportStageStep> steps;
    private final PlatformFileRuntimeRepository runtimeRepository;

    public DefaultImportStageExecutor(List<ImportStageStep> steps,
                                      PlatformFileRuntimeRepository runtimeRepository) {
        this.steps = steps;
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public List<ImportStageResult> execute(ImportJobContext context) {
        List<ImportStageResult> results = new ArrayList<>();
        Long pipelineInstanceId = runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
        String lastSuccessStage = stringValue(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE));
        for (ImportStage stage : orderedStages()) {
            runtimeRepository.updatePipelineStage(pipelineInstanceId, stage.name(), lastSuccessStage);
            Long stepRunId = runtimeRepository.startStepRun(
                    pipelineInstanceId,
                    stage.name(),
                    stage.name(),
                    buildInputSummary(context, stage)
            );
            ImportStageResult result = steps.stream()
                    .filter(step -> step.stage() == stage)
                    .findFirst()
                    .map(step -> step.execute(context))
                    .orElse(ImportStageResult.failure(stage, "IMPORT_STEP_MISSING", "step not found"));
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
    public List<ImportStage> orderedStages() {
        return List.of(
                ImportStage.RECEIVE,
                ImportStage.PREPROCESS,
                ImportStage.PARSE,
                ImportStage.VALIDATE,
                ImportStage.LOAD,
                ImportStage.FEEDBACK
        );
    }

    private Map<String, Object> buildInputSummary(ImportJobContext context, ImportStage stage) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stage", stage.name());
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
        summary.put("fileId", context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        summary.put("parsedCount", context.getAttributes().get("parsedCount"));
        summary.put("validatedCount", context.getAttributes().get("validatedCount"));
        summary.put("loadedCount", context.getAttributes().get("loadedCount"));
        return summary;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
