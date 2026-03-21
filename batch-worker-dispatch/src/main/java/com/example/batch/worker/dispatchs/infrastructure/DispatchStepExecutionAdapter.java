package com.example.batch.worker.dispatchs.infrastructure;

import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.AbstractPipelineStepExecutionAdapter;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import com.example.batch.worker.dispatchs.stage.DispatchStageExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class DispatchStepExecutionAdapter extends AbstractPipelineStepExecutionAdapter<DispatchJobContext, DispatchStageResult> {

    private final DispatchStageExecutor dispatchStageExecutor;
    private final ObjectMapper objectMapper;

    public DispatchStepExecutionAdapter(DispatchStageExecutor dispatchStageExecutor,
                                        ObjectMapper objectMapper,
                                        PlatformFileRuntimeRepository runtimeRepository) {
        super(runtimeRepository);
        this.dispatchStageExecutor = dispatchStageExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    protected String pipelineType() {
        return "DISPATCH";
    }

    @Override
    protected String pipelineDescription() {
        return "Chapter 9 dispatch pipeline";
    }

    @Override
    protected String initialStage() {
        return "PREPARE";
    }

    @Override
    protected DispatchJobContext buildContext(StepExecutionRequest request,
                                              Map<String, Object> contextMap,
                                              Long fileId) throws Exception {
        DispatchJobContext context = new DispatchJobContext();
        context.setTenantId(request.tenantId());
        context.setJobCode(String.valueOf(contextMap.getOrDefault("jobCode", request.jobCode())));
        context.setBizDate(String.valueOf(contextMap.getOrDefault("bizDate", "")));
        context.setDispatchId(String.valueOf(contextMap.getOrDefault("taskId", contextMap.getOrDefault("dispatchId", ""))));
        context.setWorkerId(request.workerId());
        context.setRawPayload(String.valueOf(contextMap.getOrDefault("payload", "")));
        context.setAttributes(contextMap);
        Object dispatchPayload = contextMap.get("dispatchPayload");
        if (dispatchPayload == null && context.getRawPayload() != null && !context.getRawPayload().isBlank()) {
            dispatchPayload = objectMapper.readValue(context.getRawPayload(), DispatchPayload.class);
            context.getAttributes().put("dispatchPayload", dispatchPayload);
        }
        return context;
    }

    @Override
    protected List<DispatchStageResult> executeStages(DispatchJobContext context) {
        return dispatchStageExecutor.execute(context);
    }

    @Override
    protected boolean isSuccess(DispatchStageResult result) {
        return result != null && result.success();
    }

    @Override
    protected String resultStage(DispatchStageResult result) {
        return result.stage().name();
    }

    @Override
    protected String resultCode(DispatchStageResult result) {
        return result.code();
    }

    @Override
    protected String resultMessage(DispatchStageResult result) {
        return result.message();
    }

    @Override
    protected StepExecutionResponse buildSuccessResponse(DispatchJobContext context,
                                                         List<DispatchStageResult> results,
                                                         Map<String, Object> attributes) {
        return new StepExecutionResponse(true, "SUCCESS", "dispatch stages executed");
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
