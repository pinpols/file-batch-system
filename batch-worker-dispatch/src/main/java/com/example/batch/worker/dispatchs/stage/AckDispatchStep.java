package com.example.batch.worker.dispatchs.stage;

import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AckDispatchStep implements DispatchStageStep {

    private final FileDispatchRepository fileDispatchRepository;
    private final PlatformFileRuntimeRepository runtimeRepository;

    public AckDispatchStep(FileDispatchRepository fileDispatchRepository,
                           PlatformFileRuntimeRepository runtimeRepository) {
        this.fileDispatchRepository = fileDispatchRepository;
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public DispatchStage stage() {
        return DispatchStage.ACK;
    }

    @Override
    public DispatchStageResult execute(DispatchJobContext context) {
        Object payload = context == null ? null : context.getAttributes().get("dispatchPayload");
        if (!(payload instanceof DispatchPayload dispatchPayload)) {
            return DispatchStageResult.failure(stage(), "DISPATCH_ACK_NO_PAYLOAD", "dispatch payload missing");
        }
        Long fileId = runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        DispatchResult dispatchResult = context.getAttributes().get("dispatchResult") instanceof DispatchResult result ? result : null;
        String receiptCode = dispatchPayload.receiptCode();
        if ((receiptCode == null || receiptCode.isBlank()) && dispatchResult != null) {
            receiptCode = dispatchResult.receiptCode();
        }
        boolean acknowledged = dispatchResult != null && dispatchResult.acknowledged();
        boolean pending = dispatchResult != null && dispatchResult.receiptPending();
        if (acknowledged || (receiptCode != null && !receiptCode.isBlank())) {
            int updated = fileDispatchRepository.markAcked(
                    context.getTenantId(),
                    fileId,
                    dispatchPayload.channelCode(),
                    receiptCode == null ? "ACK-" + fileId : receiptCode
            );
            if (updated <= 0) {
                context.getAttributes().put(
                        PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE,
                        Boolean.TRUE.equals(context.getAttributes().get("retryRequested"))
                                ? DispatchStage.RETRY.name()
                                : DispatchStage.COMPENSATE.name()
                );
                return DispatchStageResult.failure(stage(), "DISPATCH_ACK_FAILED", "failed to mark acked");
            }
            runtimeRepository.updateFileStatus(fileId, "DISPATCHED", buildFileMetadata(dispatchPayload, context, receiptCode));
            context.getAttributes().put("receiptStatus", "SUCCESS");
            return DispatchStageResult.success(stage());
        }
        if (pending || Boolean.TRUE.equals(dispatchPayload.ackRequired())) {
            context.getAttributes().put("receiptStatus", "PENDING");
            return DispatchStageResult.success(stage());
        }
        runtimeRepository.updateFileStatus(fileId, "DISPATCHED", buildFileMetadata(dispatchPayload, context, receiptCode));
        return DispatchStageResult.success(stage());
    }

    private Map<String, Object> buildFileMetadata(DispatchPayload dispatchPayload, DispatchJobContext context, String receiptCode) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channelCode", dispatchPayload.channelCode());
        metadata.put("externalRequestId", context.getAttributes().get("externalRequestId"));
        metadata.put("receiptCode", receiptCode);
        return metadata;
    }
}
