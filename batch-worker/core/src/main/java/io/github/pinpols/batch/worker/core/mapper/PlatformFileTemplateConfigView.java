package io.github.pinpols.batch.worker.core.mapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Worker 文件模板的固定列投影；JSONB 字段保留数据库驱动返回值。 */
public record PlatformFileTemplateConfigView(
    Long id,
    String tenantId,
    String templateCode,
    String templateName,
    String templateType,
    String bizType,
    String fileFormatType,
    String charset,
    String targetCharset,
    Boolean withBom,
    String lineSeparator,
    String delimiter,
    String quoteChar,
    String escapeChar,
    Integer recordLength,
    Integer headerRows,
    Integer footerRows,
    Object headerTemplate,
    Object trailerTemplate,
    String checksumType,
    String compressType,
    String encryptType,
    String namingRule,
    Object fieldMappings,
    Object validationRuleSet,
    String defaultQueryCode,
    String defaultQuerySql,
    Object queryParamSchema,
    Boolean streamingEnabled,
    Integer pageSize,
    Integer fetchSize,
    Integer chunkSize,
    Boolean enabled,
    Integer version,
    String description,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt,
    Object preprocessPipeline,
    Boolean previewMaskingEnabled,
    Boolean errorLineMaskingEnabled,
    Boolean logMaskingEnabled,
    Boolean contentEncryptionEnabled,
    String encryptionKeyRef,
    Boolean downloadRequiresApproval,
    Object maskingRuleSet,
    Object exportDataRef,
    Object loadTargetRef,
    Boolean isDeleted) {

  /** 在模板插件边界保留原有 snake_case 动态配置形状。 */
  public Map<String, Object> toPluginValues() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("id", id);
    values.put("tenant_id", tenantId);
    values.put("template_code", templateCode);
    values.put("template_name", templateName);
    values.put("template_type", templateType);
    values.put("biz_type", bizType);
    values.put("file_format_type", fileFormatType);
    values.put("charset", charset);
    values.put("target_charset", targetCharset);
    values.put("with_bom", withBom);
    values.put("line_separator", lineSeparator);
    values.put("delimiter", delimiter);
    values.put("quote_char", quoteChar);
    values.put("escape_char", escapeChar);
    values.put("record_length", recordLength);
    values.put("header_rows", headerRows);
    values.put("footer_rows", footerRows);
    values.put("header_template", headerTemplate);
    values.put("trailer_template", trailerTemplate);
    values.put("checksum_type", checksumType);
    values.put("compress_type", compressType);
    values.put("encrypt_type", encryptType);
    values.put("naming_rule", namingRule);
    values.put("field_mappings", fieldMappings);
    values.put("validation_rule_set", validationRuleSet);
    values.put("default_query_code", defaultQueryCode);
    values.put("default_query_sql", defaultQuerySql);
    values.put("query_param_schema", queryParamSchema);
    values.put("streaming_enabled", streamingEnabled);
    values.put("page_size", pageSize);
    values.put("fetch_size", fetchSize);
    values.put("chunk_size", chunkSize);
    values.put("enabled", enabled);
    values.put("version", version);
    values.put("description", description);
    values.put("created_by", createdBy);
    values.put("updated_by", updatedBy);
    values.put("created_at", createdAt);
    values.put("updated_at", updatedAt);
    values.put("preprocess_pipeline", preprocessPipeline);
    values.put("preview_masking_enabled", previewMaskingEnabled);
    values.put("error_line_masking_enabled", errorLineMaskingEnabled);
    values.put("log_masking_enabled", logMaskingEnabled);
    values.put("content_encryption_enabled", contentEncryptionEnabled);
    values.put("encryption_key_ref", encryptionKeyRef);
    values.put("download_requires_approval", downloadRequiresApproval);
    values.put("masking_rule_set", maskingRuleSet);
    values.put("export_data_ref", exportDataRef);
    values.put("load_target_ref", loadTargetRef);
    values.put("is_deleted", isDeleted);
    return values;
  }
}
