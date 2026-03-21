package com.example.batch.worker.dispatchs.stage;

import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CompensateDispatchStep implements DispatchStageStep {

    private final FileDispatchRepository fileDispatchRepository;
    private final PlatformFileRuntimeRepository runtimeRepository;

    public CompensateDispatchStep(FileDispatchRepository fileDispatchRepository,
                                  PlatformFileRuntimeRepository runtimeRepository) {
        this.fileDispatchRepository = fileDispatchRepository;
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public DispatchStage stage() {
        return DispatchStage.COMPENSATE;
    }

    @Override
    public DispatchStageResult execute(DispatchJobContext context) {
        Object payload = context == null ? null : context.getAttributes().get("dispatchPayload");
        if (!(payload instanceof DispatchPayload dispatchPayload)) {
            return DispatchStageResult.failure(stage(), "DISPATCH_COMPENSATE_NO_PAYLOAD", "dispatch payload missing");
        }
        Long fileId = runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        int updated = fileDispatchRepository.markCompensated(
                context.getTenantId(),
                fileId,
                dispatchPayload.channelCode(),
                "DISPATCH_COMPENSATED",
                "compensated"
        );
        if (updated <= 0) {
            return DispatchStageResult.failure(stage(), "DISPATCH_COMPENSATE_FAILED", "failed to mark compensated");
        }
        runtimeRepository.updateFileStatus(fileId, "FAILED", Map.of("channelCode", dispatchPayload.channelCode()));
        Map<String, Object> detailSummary = new LinkedHashMap<>();
        detailSummary.put("channelCode", dispatchPayload.channelCode());
        detailSummary.put("dispatchTarget", dispatchPayload.dispatchTarget());
        detailSummary.put("externalRequestId", context.getAttributes().get("externalRequestId"));
        runtimeRepository.appendAudit(
                fileId,
                context.getTenantId(),
                "DISPATCH_COMPENSATE",
                "FAILED",
                "SYSTEM",
                context.getWorkerId(),
                String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID)),
                null,
                detailSummary
        );
        return DispatchStageResult.success(stage());
    }
}
