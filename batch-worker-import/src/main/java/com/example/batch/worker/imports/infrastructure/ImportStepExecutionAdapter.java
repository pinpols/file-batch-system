package com.example.batch.worker.imports.infrastructure;

import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.AbstractPipelineStepExecutionAdapter;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.stage.ImportStageExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class ImportStepExecutionAdapter extends AbstractPipelineStepExecutionAdapter<ImportJobContext, ImportStageResult> {

    private final ImportStageExecutor importStageExecutor;

    public ImportStepExecutionAdapter(ImportStageExecutor importStageExecutor,
                                      PlatformFileRuntimeRepository runtimeRepository) {
        super(runtimeRepository);
        this.importStageExecutor = importStageExecutor;
    }

    @Override
    protected String pipelineType() {
        return "IMPORT";
    }

    @Override
    protected String pipelineDescription() {
        return "Chapter 9 import pipeline";
    }

    @Override
    protected String initialStage() {
        return "RECEIVE";
    }

    @Override
    protected ImportJobContext buildContext(StepExecutionRequest request,
                                            Map<String, Object> contextMap,
                                            Long fileId) {
        ImportJobContext context = new ImportJobContext();
        context.setTenantId(request.tenantId());
        context.setJobCode(String.valueOf(contextMap.getOrDefault("jobCode", request.jobCode())));
        context.setBizDate(String.valueOf(contextMap.getOrDefault("bizDate", "")));
        context.setFileId(fileId == null ? "" : String.valueOf(fileId));
        context.setWorkerId(request.workerId());
        context.setRawPayload(String.valueOf(contextMap.getOrDefault("payload", "")));
        context.setAttributes(contextMap);
        return context;
    }

    @Override
    protected List<ImportStageResult> executeStages(ImportJobContext context) {
        return importStageExecutor.execute(context);
    }

    @Override
    protected boolean isSuccess(ImportStageResult result) {
        return result != null && result.success();
    }

    @Override
    protected String resultStage(ImportStageResult result) {
        return result.stage().name();
    }

    @Override
    protected String resultCode(ImportStageResult result) {
        return result.code();
    }

    @Override
    protected String resultMessage(ImportStageResult result) {
        return result.message();
    }

    @Override
    protected StepExecutionResponse buildSuccessResponse(ImportJobContext context,
                                                         List<ImportStageResult> results,
                                                         Map<String, Object> attributes) {
        Object importedCount = context.getAttributes().getOrDefault("loadedCount", 0);
        return new StepExecutionResponse(true, "SUCCESS", "imported " + importedCount + " row(s)");
    }

    @Override
    protected void handlePipelineFailure(Map<String, Object> attributes, String errorCode, String errorMessage) {
        Long fileId = runtimeRepository().toLong(attributes.get(PipelineRuntimeKeys.FILE_ID));
        if (fileId == null) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("errorCode", errorCode);
        metadata.put("errorMessage", errorMessage);
        runtimeRepository().updateFileStatus(fileId, "FAILED", metadata);
    }
}
