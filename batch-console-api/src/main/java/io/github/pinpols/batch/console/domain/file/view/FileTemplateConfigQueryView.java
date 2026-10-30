package io.github.pinpols.batch.console.domain.file.view;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;

/** 文件模板列表的固定列投影；JSONB 字段保留驱动对象以兼容配置复制和工作簿导出。 */
@Builder
public record FileTemplateConfigQueryView(
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
    Boolean previewMaskingEnabled,
    Boolean errorLineMaskingEnabled,
    Boolean logMaskingEnabled,
    Boolean contentEncryptionEnabled,
    String encryptionKeyRef,
    Boolean downloadRequiresApproval,
    String maskingRuleSet,
    String loadTargetRef,
    String exportDataRef,
    Boolean enabled,
    Integer version,
    String description,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt) {
  /** 复刻 resultType=map 键集：snake_case、null 值不出键。仅用于 Map 消费边界。 */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    put(map, "id", id);
    put(map, "tenant_id", tenantId);
    put(map, "template_code", templateCode);
    put(map, "template_name", templateName);
    put(map, "template_type", templateType);
    put(map, "biz_type", bizType);
    put(map, "file_format_type", fileFormatType);
    put(map, "charset", charset);
    put(map, "target_charset", targetCharset);
    put(map, "with_bom", withBom);
    put(map, "line_separator", lineSeparator);
    put(map, "delimiter", delimiter);
    put(map, "quote_char", quoteChar);
    put(map, "escape_char", escapeChar);
    put(map, "record_length", recordLength);
    put(map, "header_rows", headerRows);
    put(map, "footer_rows", footerRows);
    put(map, "header_template", headerTemplate);
    put(map, "trailer_template", trailerTemplate);
    put(map, "checksum_type", checksumType);
    put(map, "compress_type", compressType);
    put(map, "encrypt_type", encryptType);
    put(map, "naming_rule", namingRule);
    put(map, "field_mappings", fieldMappings);
    put(map, "validation_rule_set", validationRuleSet);
    put(map, "default_query_code", defaultQueryCode);
    put(map, "default_query_sql", defaultQuerySql);
    put(map, "query_param_schema", queryParamSchema);
    put(map, "streaming_enabled", streamingEnabled);
    put(map, "page_size", pageSize);
    put(map, "fetch_size", fetchSize);
    put(map, "chunk_size", chunkSize);
    put(map, "preview_masking_enabled", previewMaskingEnabled);
    put(map, "error_line_masking_enabled", errorLineMaskingEnabled);
    put(map, "log_masking_enabled", logMaskingEnabled);
    put(map, "content_encryption_enabled", contentEncryptionEnabled);
    put(map, "encryption_key_ref", encryptionKeyRef);
    put(map, "download_requires_approval", downloadRequiresApproval);
    put(map, "masking_rule_set", maskingRuleSet);
    put(map, "load_target_ref", loadTargetRef);
    put(map, "export_data_ref", exportDataRef);
    put(map, "enabled", enabled);
    put(map, "version", version);
    put(map, "description", description);
    put(map, "created_by", createdBy);
    put(map, "updated_by", updatedBy);
    put(map, "created_at", createdAt);
    put(map, "updated_at", updatedAt);
    return map;
  }

  private static void put(Map<String, Object> map, String key, Object value) {
    if (EmptyChecks.isNotNull(value)) {
      map.put(key, value);
    }
  }
}
