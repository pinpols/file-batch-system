package com.example.batch.worker.imports.stage;

import com.example.batch.worker.imports.domain.CustomerImportPayload;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.infrastructure.CustomerAccountImportRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LoadStep implements ImportStageStep {

    private final CustomerAccountImportRepository customerAccountImportRepository;
    private final PlatformFileRuntimeRepository runtimeRepository;

    public LoadStep(CustomerAccountImportRepository customerAccountImportRepository,
                    PlatformFileRuntimeRepository runtimeRepository) {
        this.customerAccountImportRepository = customerAccountImportRepository;
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public ImportStage stage() {
        return ImportStage.LOAD;
    }

    @Override
    public ImportStageResult execute(ImportJobContext context) {
        Object payload = context == null ? null : context.getAttributes().get("customerPayloads");
        if (!(payload instanceof List<?> payloads) || payloads.isEmpty()) {
            return ImportStageResult.failure(stage(), "IMPORT_LOAD_NO_PAYLOAD", "customer payload missing");
        }
        @SuppressWarnings("unchecked")
        List<CustomerImportPayload> customerPayloads = (List<CustomerImportPayload>) payloads;
        ImportPayload importPayload = context.getAttributes().get("importPayload") instanceof ImportPayload item ? item : null;
        Object fileRecord = context.getAttributes().get(PipelineRuntimeKeys.FILE_RECORD);
        String sourceFileName = fileRecord instanceof Map<?, ?> row && row.get("file_name") != null
                ? String.valueOf(row.get("file_name"))
                : context.getFileId();
        int updated = customerAccountImportRepository.upsertBatch(
                context.getTenantId(),
                sourceFileName,
                importPayload == null ? context.getBizDate() : importPayload.batchNo(),
                String.valueOf(context.getAttributes().getOrDefault(PipelineRuntimeKeys.TRACE_ID, context.getWorkerId())),
                customerPayloads
        );
        if (updated <= 0) {
            return ImportStageResult.failure(stage(), "IMPORT_LOAD_FAILED", "no rows affected");
        }
        context.getAttributes().put("loadedCount", customerPayloads.size());
        runtimeRepository.updateFileStatus(
                runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
                "LOADED",
                Map.of("loadedCount", customerPayloads.size())
        );
        return ImportStageResult.success(stage());
    }
}
