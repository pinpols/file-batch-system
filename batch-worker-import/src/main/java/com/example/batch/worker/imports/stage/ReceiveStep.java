package com.example.batch.worker.imports.stage;

import com.example.batch.worker.core.infrastructure.FileRecordParam;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.domain.ImportWorkerType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReceiveStep implements ImportStageStep {

    private static final Set<String> RESERVED_METADATA_KEYS = Set.of(
            "templateCode",
            "sourceType",
            "headerRows",
            "footerRows",
            "taskId",
            "withHeader"
    );

    private final PlatformFileRuntimeRepository runtimeRepository;
    private final BatchSecurityProperties batchSecurityProperties;
    private final ObjectMapper objectMapper;

    @Value("${batch.worker.import.max-payload-size-mb:100}")
    private int maxPayloadSizeMb;

    private long maxPayloadSizeBytes;

    public ReceiveStep(PlatformFileRuntimeRepository runtimeRepository,
                       BatchSecurityProperties batchSecurityProperties,
                       ObjectMapper objectMapper) {
        this.runtimeRepository = runtimeRepository;
        this.batchSecurityProperties = batchSecurityProperties;
        this.objectMapper = objectMapper;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        this.maxPayloadSizeBytes = maxPayloadSizeMb <= 0 ? Long.MAX_VALUE : (long) maxPayloadSizeMb * 1024 * 1024;
    }

    @Override
    public ImportStage stage() {
        return ImportStage.RECEIVE;
    }

    @Override
    public ImportStageResult execute(ImportJobContext context) {
        if (context == null || !StringUtils.hasText(context.getTenantId()) || !StringUtils.hasText(context.getRawPayload())) {
            return ImportStageResult.failure(stage(), "IMPORT_RECEIVE_INVALID", "tenantId or payload is blank");
        }
        // C-5/D-4: 在任何堆内存分配前拒绝超大 payload
        long payloadLength = context.getRawPayload().length();
        if (payloadLength > maxPayloadSizeBytes) {
            return ImportStageResult.failure(stage(), "IMPORT_RECEIVE_TOO_LARGE",
                    "payload size " + payloadLength + " bytes exceeds limit " + maxPayloadSizeBytes + " bytes");
        }
        ImportPayload importPayload = resolvePayload(context);
        Long existingFileId = runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
        if (existingFileId == null) {
            String traceId = String.valueOf(context.getAttributes().getOrDefault(PipelineRuntimeKeys.TRACE_ID, context.getWorkerId()));
            String fileFormatType = normalizeFileFormat(importPayload.fileFormatType(), context.getRawPayload());
            String fileName = resolveFileName(importPayload, fileFormatType, traceId);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("templateCode", importPayload.templateCode());
            metadata.put("sourceType", defaultText(importPayload.sourceType(), "UPLOAD"));
            metadata.put("headerRows", importPayload.headerRows());
            metadata.put("footerRows", importPayload.footerRows());
            metadata.put("taskId", context.getAttributes().get(PipelineRuntimeKeys.TASK_ID));
            metadata.put("withHeader", importPayload.withHeader());
            mergeSecurityMetadata(metadata, resolveTemplateSecurity(context.getTenantId(), importPayload.templateCode()));
            mergeUserMetadata(metadata, importPayload.metadata());
            Long fileId = runtimeRepository.createFileRecord(FileRecordParam.builder()
                    .tenantId(context.getTenantId())
                    .fileCode(importPayload.fileCode())
                    .bizType(defaultText(importPayload.bizType(), context.getJobCode()))
                    .fileCategory("INPUT")
                    .fileName(fileName).originalFileName(defaultText(importPayload.originalFileName(), fileName))
                    .fileFormatType(fileFormatType).charset(defaultText(importPayload.charset(), "UTF-8"))
                    .fileSizeBytes(payloadLength)
                    .checksumType(defaultText(importPayload.checksumType(), "NONE"))
                    .checksumValue(importPayload.checksumValue())
                    .storageType(defaultText(importPayload.storageType(), "LOCAL"))
                    .storagePath(defaultText(importPayload.storagePath(), "ingress/" + context.getTenantId() + "/" + traceId + "/" + fileName))
                    .storageBucket(importPayload.storageBucket()).fileVersion(null)
                    .bizDate(parseBizDate(context.getBizDate()))
                    .sourceType(defaultText(importPayload.sourceType(), "UPLOAD"))
                    .sourceRef(importPayload.sourceRef())
                    .fileStatus("RECEIVED").traceId(traceId).metadata(metadata).build());
            context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, fileId);
            context.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, runtimeRepository.loadFileRecord(context.getTenantId(), fileId));
            runtimeRepository.bindFileToPipelineInstance(
                    runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID)),
                    fileId
            );
            context.setFileId(String.valueOf(fileId));
        }
        context.getAttributes().put("importPayload", importPayload);
        return ImportStageResult.success(stage());
    }

    private Map<String, Object> resolveTemplateSecurity(String tenantId, String templateCode) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(templateCode)) {
            return Map.of();
        }
        Map<String, Object> template = runtimeRepository.loadLatestTemplateConfig(tenantId, templateCode, ImportWorkerType.IMPORT);
        if (template == null || template.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> security = new LinkedHashMap<>();
        security.put("contentEncryptionEnabled", !batchSecurityProperties.isTestingOpen() && truthy(template.get("content_encryption_enabled")));
        security.put("encryptionKeyRef", template.get("encryption_key_ref"));
        security.put("downloadRequiresApproval", truthy(template.get("download_requires_approval")));
        security.put("previewMaskingEnabled", truthy(template.get("preview_masking_enabled")));
        security.put("errorLineMaskingEnabled", truthy(template.get("error_line_masking_enabled")));
        security.put("logMaskingEnabled", truthy(template.get("log_masking_enabled")));
        security.put("maskingRuleSet", template.get("masking_rule_set"));
        return security;
    }

    private ImportPayload resolvePayload(ImportJobContext context) {
        Object existing = context.getAttributes().get("importPayload");
        if (existing instanceof ImportPayload importPayload) {
            return importPayload;
        }
        String rawPayload = context.getRawPayload();
        if (!StringUtils.hasText(rawPayload) || !rawPayload.trim().startsWith("{")) {
            return new ImportPayload(null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, Map.of());
        }
        try {
            ImportPayload importPayload = objectMapper.readValue(rawPayload, ImportPayload.class);
            if (importPayload == null) {
                return new ImportPayload(null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, Map.of());
            }

            // content 可能不在顶层（例如 params/content）。当解析出来的 content 为空时做一次递归回填。
            if (!StringUtils.hasText(importPayload.content()) && !StringUtils.hasText(importPayload.contentBase64())) {
                JsonNode root = objectMapper.readTree(rawPayload);
                String extracted = findFirstText(root, "content");
                if (StringUtils.hasText(extracted)) {
                    String trimmed = extracted.trim();
                    // 仅接受看起来像 JSON 内容（数组或对象）的提取值。
                    if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
                        return importPayload;
                    }
                    Map<String, Object> asMap = objectMapper.convertValue(importPayload, Map.class);
                    asMap.put("content", trimmed);
                    asMap.put("contentBase64", null);
                    return objectMapper.convertValue(asMap, ImportPayload.class);
                }
            }

            return importPayload;
        } catch (Exception ignored) {
            return new ImportPayload(null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, Map.of());
        }
    }

    private String findFirstText(JsonNode node, String fieldName) {
        if (node == null || !StringUtils.hasText(fieldName)) {
            return null;
        }
        if (node.isObject()) {
            JsonNode v = node.get(fieldName);
            if (v != null && v.isTextual()) {
                return v.asText();
            }
            for (JsonNode child : node) {
                String found = findFirstText(child, fieldName);
                if (StringUtils.hasText(found)) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = findFirstText(child, fieldName);
                if (StringUtils.hasText(found)) {
                    return found;
                }
            }
        }
        return null;
    }

    private String resolveFileName(ImportPayload payload, String fileFormatType, String traceId) {
        if (StringUtils.hasText(payload.fileName())) {
            return payload.fileName();
        }
        return "import-" + traceId + switch (fileFormatType) {
            case "JSON" -> ".json";
            case "DELIMITED" -> ".csv";
            case "EXCEL" -> ".xlsx";
            default -> ".dat";
        };
    }

    private String normalizeFileFormat(String fileFormatType, String rawPayload) {
        if (StringUtils.hasText(fileFormatType)) {
            return fileFormatType.toUpperCase();
        }
        if (rawPayload != null && rawPayload.trim().startsWith("{")) {
            return "JSON";
        }
        if (rawPayload != null && rawPayload.contains(",")) {
            return "DELIMITED";
        }
        return "JSON";
    }

    private LocalDate parseBizDate(String bizDate) {
        if (!StringUtils.hasText(bizDate)) {
            return null;
        }
        try {
            return LocalDate.parse(bizDate);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
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

    private void mergeSecurityMetadata(Map<String, Object> target, Map<String, Object> security) {
        if (security == null || security.isEmpty()) {
            return;
        }
        target.putAll(security);
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }
}
