package com.example.batch.worker.exports.stage;

import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.exports.plugin.ExportDataPluginRegistry;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RegisterStep implements ExportStageStep {

    private static final Set<String> RESERVED_METADATA_KEYS = Set.of(
            "recordCount",
            "totalAmount",
            "templateCode",
            "objectName",
            "exportSnapshot"
    );

    private final PlatformFileRuntimeRepository runtimeRepository;
    private final ExportDataPluginRegistry exportDataPluginRegistry;
    private final MinioStorageProperties minioStorageProperties;

    public RegisterStep(PlatformFileRuntimeRepository runtimeRepository,
                        ExportDataPluginRegistry exportDataPluginRegistry,
                        MinioStorageProperties minioStorageProperties) {
        this.runtimeRepository = runtimeRepository;
        this.exportDataPluginRegistry = exportDataPluginRegistry;
        this.minioStorageProperties = minioStorageProperties;
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
        String bucket = minioStorageProperties.getBucket();
        String expectedChecksum = nullableText(context.getAttributes().get("checksumValue"));
        if (runtimeRepository.existsFileRecordByStoragePath(context.getTenantId(), bucket, objectName)) {
            Map<String, Object> existing = runtimeRepository.loadFileRecordByStoragePath(context.getTenantId(), bucket, objectName);
            String existingChecksum = nullableText(existing.get("checksum_value"));
            if (StringUtils.hasText(expectedChecksum) && StringUtils.hasText(existingChecksum)
                    && !expectedChecksum.equalsIgnoreCase(existingChecksum)) {
                return ExportStageResult.failure(stage(), "EXPORT_REGISTER_CHECKSUM_CONFLICT", "existing file_record checksum differs");
            }
            return reuseExistingFileRecord(context, batch, existing);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("recordCount", context.getAttributes().get("recordCount"));
        metadata.put("totalAmount", context.getAttributes().get("totalAmount"));
        metadata.put("templateCode", exportPayload.templateCode());
        metadata.put("objectName", objectName);
        mergeSecurityMetadata(metadata, context.getAttributes());
        if (context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT) != null) {
            metadata.put("exportSnapshot", context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT));
        }
        mergeUserMetadata(metadata, exportPayload.metadata());
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
                bucket,
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
        String traceId = String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID));
        resolvePlugin(context).onRegistered(buildDataContext(context, exportPayload), batchId, exportVersion, traceId);
        return ExportStageResult.success(stage());
    }

    private ExportStageResult reuseExistingFileRecord(ExportJobContext context,
                                                      Map<?, ?> batch,
                                                      Map<String, Object> existing) {
        Long fileId = runtimeRepository.toLong(existing.get("id"));
        if (fileId == null) {
            return ExportStageResult.failure(stage(), "EXPORT_REGISTER_REUSE_INVALID", "existing file id missing");
        }
        context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, fileId);
        context.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, existing);
        context.setFileId(String.valueOf(fileId));
        runtimeRepository.bindFileToPipelineInstance(
                runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID)),
                fileId
        );
        Long batchId = runtimeRepository.toLong(batch.get("id"));
        Integer exportVersion = existing.get("file_generation_no") instanceof Number number
                ? number.intValue()
                : 1;
        String traceId = String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID));
        ExportPayload exportPayload2 = (ExportPayload) context.getAttributes().get("exportPayload");
        resolvePlugin(context).onRegistered(buildDataContext(context, exportPayload2), batchId, exportVersion, traceId);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("reason", "STORE_TO_REGISTER_RETRY");
        audit.put("objectName", context.getAttributes().get("objectName"));
        runtimeRepository.appendAudit(
                fileId,
                context.getTenantId(),
                "EXPORT_REGISTER",
                "SUCCESS",
                "SYSTEM",
                context.getWorkerId(),
                String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID)),
                String.valueOf(context.getAttributes().get("objectName")),
                audit
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

    private void mergeUserMetadata(Map<String, Object> target, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (RESERVED_METADATA_KEYS.contains(entry.getKey())) {
                continue;
            }
            target.put(entry.getKey(), entry.getValue());
        }
    }

    private void mergeSecurityMetadata(Map<String, Object> target, Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        putIfPresent(target, attributes, "contentEncryptionEnabled");
        putIfPresent(target, attributes, "encryptionKeyRef");
        putIfPresent(target, attributes, "encryptionObjectVersion");
        putIfPresent(target, attributes, "downloadRequiresApproval");
    }

    private void putIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private ExportDataPlugin resolvePlugin(ExportJobContext context) {
        String exportDataRef = nullableText(context.getAttributes().get("exportDataRef"));
        return exportDataPluginRegistry.require(exportDataRef);
    }

    private ExportDataContext buildDataContext(ExportJobContext context, ExportPayload payload) {
        Object tc = context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
        Map<String, Object> templateConfig = new LinkedHashMap<>();
        if (tc instanceof Map<?, ?> m) {
            m.forEach((k, v) -> templateConfig.put(String.valueOf(k), v));
        }
        Object snap = context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (snap instanceof Map<?, ?> m) {
            m.forEach((k, v) -> snapshot.put(String.valueOf(k), v));
        }
        return new ExportDataContext(
                context.getTenantId(),
                context.getJobCode(),
                payload == null ? null : payload.batchNo(),
                payload == null ? null : payload.templateCode(),
                templateConfig,
                snapshot
        );
    }
}
