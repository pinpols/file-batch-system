package com.example.batch.worker.exports.stage;

import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.worker.core.infrastructure.FileAuditParam;
import com.example.batch.worker.core.infrastructure.FileRecordParam;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.exports.plugin.ExportDataPluginRegistry;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.example.batch.common.utils.Texts;

/** 导出注册阶段：在平台创建 file_record，并将文件与 pipeline 实例绑定，触发插件 onRegistered 回调。 */
@Component
public class RegisterStep implements ExportStageStep {

  private static final String KEY_OBJECT_NAME = "objectName";

  private static final Set<String> RESERVED_METADATA_KEYS =
      Set.of("recordCount", "totalAmount", "templateCode", KEY_OBJECT_NAME, "exportSnapshot");

  private final PlatformFileRuntimeRepository runtimeRepository;
  private final ExportDataPluginRegistry exportDataPluginRegistry;
  private final MinioStorageProperties minioStorageProperties;

  public RegisterStep(
      PlatformFileRuntimeRepository runtimeRepository,
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
    if (context == null || context.getAttributes().get(KEY_OBJECT_NAME) == null) {
      return ExportStageResult.failure(stage(), "EXPORT_REGISTER_INVALID", "objectName missing");
    }
    Object payload = context.getAttributes().get("exportPayload");
    Object batchObject = context.getAttributes().get("exportBatch");
    if (!(payload instanceof ExportPayload exportPayload)
        || !(batchObject instanceof Map<?, ?> batch)) {
      return ExportStageResult.failure(
          stage(), "EXPORT_REGISTER_INVALID", "export context missing");
    }
    String objectName = String.valueOf(context.getAttributes().get(KEY_OBJECT_NAME));
    String fileName = String.valueOf(context.getAttributes().get("fileName"));
    String fileFormatType =
        String.valueOf(context.getAttributes().getOrDefault("exportFileFormatType", "JSON"));
    String bucket = minioStorageProperties.getBucket();
    String expectedChecksum = nullableText(context.getAttributes().get("checksumValue"));
    // 相同路径的 file_record 已存在时进行幂等复用（STORE → REGISTER 重试场景）
    if (runtimeRepository.existsFileRecordByStoragePath(
        context.getTenantId(), bucket, objectName)) {
      Map<String, Object> existing =
          runtimeRepository.loadFileRecordByStoragePath(context.getTenantId(), bucket, objectName);
      String existingChecksum = nullableText(existing.get("checksum_value"));
      if (Texts.hasText(expectedChecksum)
          && Texts.hasText(existingChecksum)
          && !expectedChecksum.equalsIgnoreCase(existingChecksum)) {
        return ExportStageResult.failure(
            stage(), "EXPORT_REGISTER_CHECKSUM_CONFLICT", "已有 file_record 的校验值不一致");
      }
      return reuseExistingFileRecord(context, batch, existing);
    }

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("recordCount", context.getAttributes().get("recordCount"));
    metadata.put("totalAmount", context.getAttributes().get("totalAmount"));
    metadata.put("templateCode", exportPayload.templateCode());
    metadata.put(KEY_OBJECT_NAME, objectName);
    mergeSecurityMetadata(metadata, context.getAttributes());
    if (context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT) != null) {
      metadata.put(
          "exportSnapshot", context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT));
    }
    mergeUserMetadata(metadata, exportPayload.metadata());
    Long fileId =
        runtimeRepository.createFileRecord(
            FileRecordParam.builder()
                .tenantId(context.getTenantId())
                .fileCode(exportPayload.fileCode())
                .bizType(
                    Texts.hasText(exportPayload.bizType())
                        ? exportPayload.bizType()
                        : context.getJobCode())
                .fileCategory("OUTPUT")
                .fileName(fileName)
                .originalFileName(fileName)
                .fileFormatType(fileFormatType)
                .charset(StandardCharsets.UTF_8.name())
                .fileSizeBytes(
                    runtimeRepository.toLong(context.getAttributes().get("fileSizeBytes")) == null
                        ? 0L
                        : runtimeRepository.toLong(context.getAttributes().get("fileSizeBytes")))
                .checksumType(
                    String.valueOf(context.getAttributes().getOrDefault("checksumType", "SHA-256")))
                .checksumValue(nullableText(context.getAttributes().get("checksumValue")))
                .storageType("S3")
                .storagePath(objectName)
                .storageBucket(bucket)
                .fileVersion(null)
                .bizDate(parseBizDate(exportPayload.bizDate(), context.getBizDate()))
                .sourceType("GENERATED")
                .sourceRef(exportPayload.batchNo())
                .fileStatus("GENERATED")
                .traceId(String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID)))
                .metadata(metadata)
                .build());
    Map<String, Object> fileRecord =
        runtimeRepository.loadFileRecord(context.getTenantId(), fileId);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, fileId);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, fileRecord);
    context.setFileId(String.valueOf(fileId));
    runtimeRepository.bindFileToPipelineInstance(
        runtimeRepository.toLong(
            context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID)),
        fileId);
    Long batchId = runtimeRepository.toLong(batch.get("id"));
    Integer exportVersion =
        fileRecord.get("file_generation_no") instanceof Number number ? number.intValue() : 1;
    String traceId = String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID));
    resolvePlugin(context)
        .onRegistered(buildDataContext(context, exportPayload), batchId, exportVersion, traceId);
    return ExportStageResult.success(stage());
  }

  private ExportStageResult reuseExistingFileRecord(
      ExportJobContext context, Map<?, ?> batch, Map<String, Object> existing) {
    Long fileId = runtimeRepository.toLong(existing.get("id"));
    if (fileId == null) {
      return ExportStageResult.failure(
          stage(), "EXPORT_REGISTER_REUSE_INVALID", "existing file id missing");
    }
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, fileId);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, existing);
    context.setFileId(String.valueOf(fileId));
    runtimeRepository.bindFileToPipelineInstance(
        runtimeRepository.toLong(
            context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID)),
        fileId);
    Long batchId = runtimeRepository.toLong(batch.get("id"));
    Integer exportVersion =
        existing.get("file_generation_no") instanceof Number number ? number.intValue() : 1;
    String traceId = String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID));
    ExportPayload exportPayload2 = (ExportPayload) context.getAttributes().get("exportPayload");
    resolvePlugin(context)
        .onRegistered(buildDataContext(context, exportPayload2), batchId, exportVersion, traceId);
    Map<String, Object> audit = new LinkedHashMap<>();
    audit.put("reason", "STORE_TO_REGISTER_RETRY");
    audit.put(KEY_OBJECT_NAME, context.getAttributes().get(KEY_OBJECT_NAME));
    runtimeRepository.appendAudit(
        FileAuditParam.builder()
            .fileId(fileId)
            .tenantId(context.getTenantId())
            .operationType("EXPORT_REGISTER")
            .operationResult("SUCCESS")
            .operatorType("SYSTEM")
            .operatorId(context.getWorkerId())
            .traceId(String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID)))
            .evidenceRef(String.valueOf(context.getAttributes().get(KEY_OBJECT_NAME)))
            .detailSummary(audit)
            .build());
    return ExportStageResult.success(stage());
  }

  private LocalDate parseBizDate(String payloadBizDate, String fallbackBizDate) {
    String bizDate = Texts.hasText(payloadBizDate) ? payloadBizDate : fallbackBizDate;
    if (!Texts.hasText(bizDate)) {
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
    return Texts.hasText(text) && !"null".equalsIgnoreCase(text) ? text : null;
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
        snapshot);
  }
}
