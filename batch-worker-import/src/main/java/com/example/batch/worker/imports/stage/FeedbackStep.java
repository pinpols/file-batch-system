package com.example.batch.worker.imports.stage;

import com.example.batch.worker.core.infrastructure.FileAuditParam;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import org.springframework.stereotype.Component;

@Component
public class FeedbackStep implements ImportStageStep {

    private final PlatformFileRuntimeRepository runtimeRepository;

    public FeedbackStep(PlatformFileRuntimeRepository runtimeRepository) {
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public ImportStage stage() {
        return ImportStage.FEEDBACK;
    }

    @Override
    public ImportStageResult execute(ImportJobContext context) {
        Long fileId = runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        Map<String, Object> detailSummary = new LinkedHashMap<>();
        detailSummary.put("parsedCount", context.getAttributes().get("parsedCount"));
        detailSummary.put("validatedCount", context.getAttributes().get("validatedCount"));
        detailSummary.put("loadedCount", context.getAttributes().get("loadedCount"));
        detailSummary.put("pipelineInstanceId", context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
        runtimeRepository.appendAudit(FileAuditParam.builder()
                .fileId(fileId).tenantId(context.getTenantId())
                .operationType("IMPORT_FEEDBACK").operationResult("SUCCESS")
                .operatorType("SYSTEM").operatorId(context.getWorkerId())
                .traceId(String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID)))
                .evidenceRef(null).detailSummary(detailSummary).build());
        return ImportStageResult.success(stage());
    }
}
