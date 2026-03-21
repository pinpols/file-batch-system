package com.example.batch.worker.dispatchs.stage;

import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CompleteDispatchStep implements DispatchStageStep {

    private final PlatformFileRuntimeRepository runtimeRepository;

    public CompleteDispatchStep(PlatformFileRuntimeRepository runtimeRepository) {
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public DispatchStage stage() {
        return DispatchStage.COMPLETE;
    }

    @Override
    public DispatchStageResult execute(DispatchJobContext context) {
        Object payload = context == null ? null : context.getAttributes().get("dispatchPayload");
        if (!(payload instanceof DispatchPayload dispatchPayload)) {
            return DispatchStageResult.failure(stage(), "DISPATCH_COMPLETE_NO_PAYLOAD", "dispatch payload missing");
        }
        Long fileId = runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        String receiptStatus = String.valueOf(context.getAttributes().getOrDefault("receiptStatus", "NONE"));
        if ("SUCCESS".equalsIgnoreCase(receiptStatus)) {
            Map<String, Object> fileMetadata = new LinkedHashMap<>();
            fileMetadata.put("channelCode", dispatchPayload.channelCode());
            if (context.getAttributes().get("receiptCode") != null) {
                fileMetadata.put("receiptCode", context.getAttributes().get("receiptCode"));
            }
            runtimeRepository.updateFileStatus(fileId, "DISPATCHED", fileMetadata);
        }
        Map<String, Object> detailSummary = new LinkedHashMap<>();
        detailSummary.put("channelCode", dispatchPayload.channelCode());
        detailSummary.put("dispatchTarget", dispatchPayload.dispatchTarget());
        detailSummary.put("externalRequestId", context.getAttributes().get("externalRequestId"));
        detailSummary.put("receiptCode", context.getAttributes().get("receiptCode"));
        detailSummary.put("receiptStatus", receiptStatus);
        runtimeRepository.appendAudit(
                fileId,
                context.getTenantId(),
                "DISPATCH_COMPLETE",
                "SUCCESS",
                "SYSTEM",
                context.getWorkerId(),
                String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID)),
                String.valueOf(context.getAttributes().getOrDefault("externalRequestId", "")),
                detailSummary
        );
        return DispatchStageResult.success(stage());
    }
}
