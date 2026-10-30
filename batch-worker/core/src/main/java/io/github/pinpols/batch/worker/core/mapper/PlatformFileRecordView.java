package io.github.pinpols.batch.worker.core.mapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Worker 文件记录的固定列投影。 */
public record PlatformFileRecordView(
    Long id,
    String tenantId,
    String fileCode,
    String bizType,
    String fileCategory,
    String fileName,
    String originalFileName,
    String fileExt,
    String fileFormatType,
    String charset,
    String mimeType,
    Long fileSizeBytes,
    String checksumType,
    String checksumValue,
    String storageType,
    String storagePath,
    String storageBucket,
    String fileVersion,
    Integer fileGenerationNo,
    Boolean isLatest,
    String sourceType,
    String sourceRef,
    String fileStatus,
    LocalDate bizDate,
    String traceId,
    Object metadataJson,
    Instant createdAt,
    Instant updatedAt) {

  /** 在文件插件边界保留原有 snake_case 动态配置形状。 */
  public Map<String, Object> toPluginValues() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("id", id);
    values.put("tenant_id", tenantId);
    values.put("file_code", fileCode);
    values.put("biz_type", bizType);
    values.put("file_category", fileCategory);
    values.put("file_name", fileName);
    values.put("original_file_name", originalFileName);
    values.put("file_ext", fileExt);
    values.put("file_format_type", fileFormatType);
    values.put("charset", charset);
    values.put("mime_type", mimeType);
    values.put("file_size_bytes", fileSizeBytes);
    values.put("checksum_type", checksumType);
    values.put("checksum_value", checksumValue);
    values.put("storage_type", storageType);
    values.put("storage_path", storagePath);
    values.put("storage_bucket", storageBucket);
    values.put("file_version", fileVersion);
    values.put("file_generation_no", fileGenerationNo);
    values.put("is_latest", isLatest);
    values.put("source_type", sourceType);
    values.put("source_ref", sourceRef);
    values.put("file_status", fileStatus);
    values.put("biz_date", bizDate);
    values.put("trace_id", traceId);
    values.put("metadata_json", metadataJson);
    values.put("created_at", createdAt);
    values.put("updated_at", updatedAt);
    return values;
  }
}
