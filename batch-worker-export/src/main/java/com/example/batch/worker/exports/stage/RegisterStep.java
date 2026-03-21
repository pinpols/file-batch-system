package com.example.batch.worker.exports.stage;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.exports.infrastructure.SettlementExportRepository;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RegisterStep implements ExportStageStep {

    private final PlatformFileRuntimeRepository runtimeRepository;
    private final SettlementExportRepository settlementExportRepository;

    public RegisterStep(PlatformFileRuntimeRepository runtimeRepository,
                        SettlementExportRepository settlementExportRepository) {
        this.runtimeRepository = runtimeRepository;
        this.settlementExportRepository = settlementExportRepository;
    }

    @Override
    public ExportStage stage() {
        return ExportStage.REGISTER;
    }

    @Override
    public ExportStageResult execute(ExportJobContext context) {
        if (context == null || context.getAttributes().get("objectName") == null) {
            return ExportStageResult.failure(stage(), "EXPORT_REGISTER_INVALID", "objectName missing");
        }
        Object payload = context.getAttributes().get("exportPayload");
        Object batchObject = context.getAttributes().get("exportBatch");
        if (!(payload instanceof ExportPayload exportPayload) || !(batchObject instanceof Map<?, ?> batch)) {
            return ExportStageResult.failure(stage(), "EXPORT_REGISTER_INVALID", "export context missing");
        }
        String objectName = String.valueOf(context.getAttributes().get("objectName"));
        String fileName = String.valueOf(context.getAttributes().get("fileName"));
        String fileFormatType = String.valueOf(context.getAttributes().getOrDefault("exportFileFormatType", "JSON"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("recordCount", context.getAttributes().get("recordCount"));
        metadata.put("totalAmount", context.getAttributes().get("totalAmount"));
        metadata.put("templateCode", exportPayload.templateCode());
        metadata.put("objectName", objectName);
        if (exportPayload.metadata() != null) {
            metadata.putAll(exportPayload.metadata());
        }
        Long fileId = runtimeRepository.createFileRecord(
                context.getTenantId(),
                exportPayload.fileCode(),
                StringUtils.hasText(exportPayload.bizType()) ? exportPayload.bizType() : context.getJobCode(),
                "OUTPUT",
                fileName,
                fileName,
                fileFormatType,
                "UTF-8",
                runtimeRepository.toLong(context.getAttributes().get("fileSizeBytes")) == null
                        ? 0L
                        : runtimeRepository.toLong(context.getAttributes().get("fileSizeBytes")),
                String.valueOf(context.getAttributes().getOrDefault("checksumType", "SHA-256")),
                nullableText(context.getAttributes().get("checksumValue")),
                "S3",
                objectName,
                null,
                null,
                parseBizDate(exportPayload.bizDate(), context.getBizDate()),
                "GENERATED",
                exportPayload.batchNo(),
                "GENERATED",
                String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID)),
                metadata
        );
        Map<String, Object> fileRecord = runtimeRepository.loadFileRecord(context.getTenantId(), fileId);
        context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, fileId);
        context.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, fileRecord);
        context.setFileId(String.valueOf(fileId));
        runtimeRepository.bindFileToPipelineInstance(
                runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID)),
                fileId
        );
        Long batchId = runtimeRepository.toLong(batch.get("id"));
        Integer exportVersion = fileRecord.get("file_generation_no") instanceof Number number
                ? number.intValue()
                : 1;
        settlementExportRepository.markBatchExported(context.getTenantId(), batchId);
        settlementExportRepository.markDetailsExported(
                context.getTenantId(),
                batchId,
                exportVersion,
                String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID))
        );
        return ExportStageResult.success(stage());
    }

    private LocalDate parseBizDate(String payloadBizDate, String fallbackBizDate) {
        String bizDate = StringUtils.hasText(payloadBizDate) ? payloadBizDate : fallbackBizDate;
        if (!StringUtils.hasText(bizDate)) {
            return null;
        }
        try {
            return LocalDate.parse(bizDate);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String nullableText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) && !"null".equalsIgnoreCase(text) ? text : null;
    }
}
