package com.example.batch.worker.dispatchs.stage;

import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchChannelGateway;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchCommand;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DeliverDispatchStep implements DispatchStageStep {

    private final FileDispatchRepository fileDispatchRepository;
    private final DispatchChannelGateway dispatchChannelGateway;
    private final PlatformFileRuntimeRepository runtimeRepository;

    public DeliverDispatchStep(FileDispatchRepository fileDispatchRepository,
                               DispatchChannelGateway dispatchChannelGateway,
                               PlatformFileRuntimeRepository runtimeRepository) {
        this.fileDispatchRepository = fileDispatchRepository;
        this.dispatchChannelGateway = dispatchChannelGateway;
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public DispatchStage stage() {
        return DispatchStage.DISPATCH;
    }

    @Override
    public DispatchStageResult execute(DispatchJobContext context) {
        Object payload = context == null ? null : context.getAttributes().get("dispatchPayload");
        if (!(payload instanceof DispatchPayload dispatchPayload)) {
            return DispatchStageResult.failure(stage(), "DISPATCH_LOAD_NO_PAYLOAD", "dispatch payload missing");
        }
        Long fileId = runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        @SuppressWarnings("unchecked")
        Map<String, Object> fileRecord = (Map<String, Object>) context.getAttributes().get(PipelineRuntimeKeys.FILE_RECORD);
        @SuppressWarnings("unchecked")
        Map<String, Object> channelConfig = (Map<String, Object>) context.getAttributes().get(PipelineRuntimeKeys.CHANNEL_CONFIG);
        if (fileId == null || fileRecord == null || channelConfig == null) {
            return DispatchStageResult.failure(stage(), "DISPATCH_PREPARE_MISSING", "file or channel context missing");
        }
        Map<String, Object> latestRecord = fileDispatchRepository.loadLatestDispatchRecord(context.getTenantId(), fileId, dispatchPayload.channelCode());
        if (latestRecord.isEmpty()) {
            int inserted = fileDispatchRepository.insertDispatchRecord(
                    context.getTenantId(),
                    fileId,
                    runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID)),
                    dispatchPayload.channelCode(),
                    dispatchPayload.dispatchTarget(),
                    dispatchPayload.receiptCode(),
                    "NONE",
                    dispatchPayload.externalRequestId()
            );
            if (inserted <= 0) {
                return DispatchStageResult.failure(stage(), "DISPATCH_INSERT_FAILED", "failed to create dispatch record");
            }
        } else {
            fileDispatchRepository.incrementAttempt(context.getTenantId(), fileId, dispatchPayload.channelCode());
        }
        DispatchResult dispatchResult = dispatchChannelGateway.dispatch(new DispatchCommand(
                context.getTenantId(),
                String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID)),
                fileRecord,
                channelConfig,
                dispatchPayload
        ));
        context.getAttributes().put("dispatchResult", dispatchResult);
        context.getAttributes().put("externalRequestId", dispatchResult.externalRequestId());
        context.getAttributes().put("receiptCode", dispatchResult.receiptCode());
        context.getAttributes().put("receiptStatus", dispatchResult.acknowledged() ? "SUCCESS" : dispatchResult.receiptPending() ? "PENDING" : "NONE");
        Map<String, Object> fileMetadata = new java.util.LinkedHashMap<>();
        fileMetadata.put("channelCode", dispatchPayload.channelCode());
        if (dispatchResult.externalRequestId() != null) {
            fileMetadata.put("externalRequestId", dispatchResult.externalRequestId());
        }
        runtimeRepository.updateFileStatus(fileId, "DISPATCHING", fileMetadata);
        if (!dispatchResult.success()) {
            context.getAttributes().put("retryRequested", Boolean.TRUE);
            context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.RETRY.name());
            fileDispatchRepository.markFailed(
                    context.getTenantId(),
                    fileId,
                    dispatchPayload.channelCode(),
                    "DISPATCH_DELIVERY_FAILED",
                    dispatchResult.message()
            );
            return DispatchStageResult.failure(stage(), "DISPATCH_SEND_FAILED", dispatchResult.message());
        }
        int updated = fileDispatchRepository.markSent(
                context.getTenantId(),
                fileId,
                dispatchPayload.channelCode(),
                dispatchResult.externalRequestId(),
                dispatchResult.receiptCode(),
                dispatchResult.acknowledged() ? "SUCCESS" : dispatchResult.receiptPending() ? "PENDING" : "NONE"
        );
        if (updated <= 0) {
            return DispatchStageResult.failure(stage(), "DISPATCH_SEND_FAILED", "failed to mark sent");
        }
        context.getAttributes().put("dispatchRecord", dispatchPayload);
        return DispatchStageResult.success(stage());
    }
}
