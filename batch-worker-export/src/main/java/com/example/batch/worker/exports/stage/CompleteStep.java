package com.example.batch.worker.exports.stage;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CompleteStep implements ExportStageStep {

    private final PlatformFileRuntimeRepository runtimeRepository;

    public CompleteStep(PlatformFileRuntimeRepository runtimeRepository) {
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public ExportStage stage() {
        return ExportStage.COMPLETE;
    }

    @Override
    public ExportStageResult execute(ExportJobContext context) {
        if (context == null || context.getAttributes().get("objectName") == null) {
            return ExportStageResult.failure(stage(), "EXPORT_COMPLETE_INVALID", "objectName missing");
        }
        ExportPayload exportPayload = context.getAttributes().get("exportPayload") instanceof ExportPayload payload ? payload : null;
        Long fileId = runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        String nextStatus = exportPayload != null && Boolean.TRUE.equals(exportPayload.autoDispatch()) ? "DISPATCHING" : "GENERATED";
        Map<String, Object> fileMetadata = new LinkedHashMap<>();
        fileMetadata.put("recordCount", context.getAttributes().get("recordCount"));
        fileMetadata.put("objectName", context.getAttributes().get("objectName"));
        if (context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT) != null) {
            fileMetadata.put("exportSnapshot", context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT));
        }
        runtimeRepository.updateFileStatus(fileId, nextStatus, fileMetadata);
        Map<String, Object> detailSummary = new LinkedHashMap<>();
        detailSummary.put("recordCount", context.getAttributes().get("recordCount"));
        detailSummary.put("fileSizeBytes", context.getAttributes().get("fileSizeBytes"));
        detailSummary.put("objectName", context.getAttributes().get("objectName"));
        runtimeRepository.appendAudit(
                fileId,
                context.getTenantId(),
                "EXPORT_COMPLETE",
                "SUCCESS",
                "SYSTEM",
                context.getWorkerId(),
                String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID)),
                String.valueOf(context.getAttributes().get("objectName")),
                detailSummary
        );
        return ExportStageResult.success(stage());
    }
}
