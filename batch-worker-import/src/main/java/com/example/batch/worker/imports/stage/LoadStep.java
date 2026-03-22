package com.example.batch.worker.imports.stage;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.CustomerImportPayload;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.infrastructure.CustomerAccountImportRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoadStep implements ImportStageStep {

    private final CustomerAccountImportRepository customerAccountImportRepository;
    private final PlatformFileRuntimeRepository runtimeRepository;

    @Override
    public ImportStage stage() {
        return ImportStage.LOAD;
    }

    @Override
    public ImportStageResult execute(ImportJobContext context) {
        Object payload = context == null ? null : context.getAttributes().get("customerPayloads");
        if (!(payload instanceof List<?> payloads) || payloads.isEmpty()) {
            if (numberValue(context.getAttributes().get("skippedCount")) > 0) {
                runtimeRepository.updateFileStatus(
                        runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
                        "LOADED",
                        Map.of(
                                "loadedCount", 0,
                                "skippedCount", numberValue(context.getAttributes().get("skippedCount")),
                                "badRecordCount", badRecordCount(context),
                                "manualReviewRequired", Boolean.TRUE.equals(context.getAttributes().get("manualReviewRequired"))
                        )
                );
                return ImportStageResult.success(stage());
            }
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
        if (updated < 0) {
            return ImportStageResult.failure(stage(), "IMPORT_LOAD_FAILED", "load returned negative count");
        }
        context.getAttributes().put("loadedCount", customerPayloads.size());
        context.getAttributes().put("successCount", numberValue(context.getAttributes().get("successCount")) + customerPayloads.size());
        runtimeRepository.updateFileStatus(
                runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
                "LOADED",
                Map.of(
                        "loadedCount", customerPayloads.size(),
                        "successCount", numberValue(context.getAttributes().get("successCount")),
                        "skippedCount", numberValue(context.getAttributes().get("skippedCount")),
                        "badRecordCount", badRecordCount(context),
                        "manualReviewRequired", Boolean.TRUE.equals(context.getAttributes().get("manualReviewRequired"))
                )
        );
        return ImportStageResult.success(stage());
    }

    private long badRecordCount(ImportJobContext context) {
        Object value = context.getAttributes().get("badRecords");
        if (value instanceof List<?> list) {
            return list.size();
        }
        return 0L;
    }

    private long numberValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return 0L;
        }
        return Long.parseLong(text);
    }
}
