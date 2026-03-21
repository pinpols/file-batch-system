package com.example.batch.worker.exports.infrastructure;

import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.AbstractPipelineStepExecutionAdapter;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.exports.stage.ExportStageExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class ExportStepExecutionAdapter extends AbstractPipelineStepExecutionAdapter<ExportJobContext, ExportStageResult> {

    private final ExportStageExecutor exportStageExecutor;
    private final ObjectMapper objectMapper;

    public ExportStepExecutionAdapter(ExportStageExecutor exportStageExecutor,
                                      ObjectMapper objectMapper,
                                      PlatformFileRuntimeRepository runtimeRepository) {
        super(runtimeRepository);
        this.exportStageExecutor = exportStageExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    protected String pipelineType() {
        return "EXPORT";
    }

    @Override
    protected String pipelineDescription() {
        return "Chapter 9 export pipeline";
    }

    @Override
    protected String initialStage() {
        return "PREPARE";
    }

    @Override
    protected ExportJobContext buildContext(StepExecutionRequest request,
                                            Map<String, Object> contextMap,
                                            Long fileId) throws Exception {
        ExportJobContext context = new ExportJobContext();
        context.setTenantId(request.tenantId());
        context.setJobCode(String.valueOf(contextMap.getOrDefault("jobCode", request.jobCode())));
        context.setBizDate(String.valueOf(contextMap.getOrDefault("bizDate", "")));
        context.setFileId(fileId == null ? "" : String.valueOf(fileId));
        context.setWorkerId(request.workerId());
        context.setRawPayload(String.valueOf(contextMap.getOrDefault("payload", "")));
        context.setAttributes(contextMap);
        Object exportPayload = contextMap.get("exportPayload");
        if (exportPayload == null && context.getRawPayload() != null && !context.getRawPayload().isBlank()) {
            exportPayload = objectMapper.readValue(context.getRawPayload(), ExportPayload.class);
            context.getAttributes().put("exportPayload", exportPayload);
        }
        return context;
    }

    @Override
    protected List<ExportStageResult> executeStages(ExportJobContext context) {
        return exportStageExecutor.execute(context);
    }

    @Override
    protected boolean isSuccess(ExportStageResult result) {
        return result != null && result.success();
    }

    @Override
    protected String resultStage(ExportStageResult result) {
        return result.stage().name();
    }

    @Override
    protected String resultCode(ExportStageResult result) {
        return result.code();
    }

    @Override
    protected String resultMessage(ExportStageResult result) {
        return result.message();
    }

    @Override
    protected StepExecutionResponse buildSuccessResponse(ExportJobContext context,
                                                         List<ExportStageResult> results,
                                                         Map<String, Object> attributes) {
        String objectName = String.valueOf(context.getAttributes().getOrDefault("objectName", ""));
        return new StepExecutionResponse(true, "SUCCESS", objectName.isBlank() ? "export stages executed" : objectName);
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
