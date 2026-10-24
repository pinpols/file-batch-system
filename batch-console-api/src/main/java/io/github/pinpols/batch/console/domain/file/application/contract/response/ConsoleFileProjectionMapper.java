package io.github.pinpols.batch.console.domain.file.application.contract.response;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigQueryView;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** 将文件配置 mapper 的下划线列投影转换为稳定的 Console API record。 */
public final class ConsoleFileProjectionMapper {

  private ConsoleFileProjectionMapper() {}

  public static ConsoleFileChannelResponse channel(Map<String, Object> row) {
    if (EmptyChecks.isNull(row)) {
      return null;
    }
    Long id = longValue(row, "id");
    String tenantId = stringValue(row, "tenant_id");
    String channelCode = stringValue(row, "channel_code");
    String channelName = stringValue(row, "channel_name");
    String channelType = stringValue(row, "channel_type");
    String rawTargetEndpoint = stringValue(row, "target_endpoint");
    String maskedTargetEndpoint = SecretMasking.maskEndpoint(rawTargetEndpoint);
    String authType = stringValue(row, "auth_type");
    String configJson = safeMaskedConfigJson(row);
    String receiptPolicy = stringValue(row, "receipt_policy");
    Integer timeoutSeconds = intValue(row, "timeout_seconds");
    Boolean enabled = booleanValue(row, "enabled");
    Instant createdAt = instantValue(row, "created_at");
    Instant updatedAt = instantValue(row, "updated_at");
    return ConsoleFileChannelResponse.builder()
        .id(id)
        .tenantId(tenantId)
        .channelCode(channelCode)
        .channelName(channelName)
        .channelType(channelType)
        .targetEndpoint(maskedTargetEndpoint)
        .authType(authType)
        .configJson(configJson)
        .receiptPolicy(receiptPolicy)
        .timeoutSeconds(timeoutSeconds)
        .enabled(enabled)
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .build();
  }

  public static ConsoleFileChannelResponse channelView(FileChannelConfigQueryView row) {
    if (EmptyChecks.isNull(row)) {
      return null;
    }
    String configJson = safeMaskedConfigJson(row.configJson());
    return ConsoleFileChannelResponse.builder()
        .id(row.id())
        .tenantId(row.tenantId())
        .channelCode(row.channelCode())
        .channelName(row.channelName())
        .channelType(row.channelType())
        .targetEndpoint(SecretMasking.maskEndpoint(row.targetEndpoint()))
        .authType(row.authType())
        .configJson(configJson)
        .receiptPolicy(row.receiptPolicy())
        .timeoutSeconds(row.timeoutSeconds())
        .enabled(row.enabled())
        .createdAt(row.createdAt())
        .updatedAt(row.updatedAt())
        .build();
  }

  private static String safeMaskedConfigJson(Map<String, Object> row) {
    return safeMaskedConfigJson(row.get("config_json"));
  }

  private static String safeMaskedConfigJson(Object value) {
    try {
      String configJson = value == null ? null : value.toString();
      return SecretMasking.maskSensitiveJson(configJson);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  public static ConsoleFileTemplateResponse template(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return ConsoleFileTemplateResponse.builder()
        .id(longValue(row, "id"))
        .tenantId(stringValue(row, "tenant_id"))
        .templateCode(stringValue(row, "template_code"))
        .templateName(stringValue(row, "template_name"))
        .templateType(stringValue(row, "template_type"))
        .bizType(stringValue(row, "biz_type"))
        .fileFormatType(stringValue(row, "file_format_type"))
        .charset(stringValue(row, "charset"))
        .targetCharset(stringValue(row, "target_charset"))
        .withBom(booleanValue(row, "with_bom"))
        .lineSeparator(stringValue(row, "line_separator"))
        .delimiter(stringValue(row, "delimiter"))
        .quoteChar(stringValue(row, "quote_char"))
        .escapeChar(stringValue(row, "escape_char"))
        .recordLength(intValue(row, "record_length"))
        .headerRows(intValue(row, "header_rows"))
        .footerRows(intValue(row, "footer_rows"))
        .headerTemplate(stringValue(row, "header_template"))
        .trailerTemplate(stringValue(row, "trailer_template"))
        .checksumType(stringValue(row, "checksum_type"))
        .compressType(stringValue(row, "compress_type"))
        .encryptType(stringValue(row, "encrypt_type"))
        .namingRule(stringValue(row, "naming_rule"))
        .fieldMappings(stringValue(row, "field_mappings"))
        .validationRuleSet(stringValue(row, "validation_rule_set"))
        .defaultQueryCode(stringValue(row, "default_query_code"))
        .defaultQuerySql(stringValue(row, "default_query_sql"))
        .queryParamSchema(stringValue(row, "query_param_schema"))
        .streamingEnabled(booleanValue(row, "streaming_enabled"))
        .pageSize(intValue(row, "page_size"))
        .fetchSize(intValue(row, "fetch_size"))
        .chunkSize(intValue(row, "chunk_size"))
        .previewMaskingEnabled(booleanValue(row, "preview_masking_enabled"))
        .errorLineMaskingEnabled(booleanValue(row, "error_line_masking_enabled"))
        .logMaskingEnabled(booleanValue(row, "log_masking_enabled"))
        .contentEncryptionEnabled(booleanValue(row, "content_encryption_enabled"))
        .encryptionKeyRef(stringValue(row, "encryption_key_ref"))
        .downloadRequiresApproval(booleanValue(row, "download_requires_approval"))
        .maskingRuleSet(stringValue(row, "masking_rule_set"))
        .enabled(booleanValue(row, "enabled"))
        .version(intValue(row, "version"))
        .description(stringValue(row, "description"))
        .createdBy(stringValue(row, "created_by"))
        .updatedBy(stringValue(row, "updated_by"))
        .createdAt(instantValue(row, "created_at"))
        .updatedAt(instantValue(row, "updated_at"))
        .build();
  }

  public static ConsoleFileTemplateResponse templateView(FileTemplateConfigQueryView row) {
    if (row == null) {
      return null;
    }
    return ConsoleFileTemplateResponse.builder()
        .id(row.id())
        .tenantId(row.tenantId())
        .templateCode(row.templateCode())
        .templateName(row.templateName())
        .templateType(row.templateType())
        .bizType(row.bizType())
        .fileFormatType(row.fileFormatType())
        .charset(row.charset())
        .targetCharset(row.targetCharset())
        .withBom(row.withBom())
        .lineSeparator(row.lineSeparator())
        .delimiter(row.delimiter())
        .quoteChar(row.quoteChar())
        .escapeChar(row.escapeChar())
        .recordLength(row.recordLength())
        .headerRows(row.headerRows())
        .footerRows(row.footerRows())
        .headerTemplate(stringValue(row.headerTemplate()))
        .trailerTemplate(stringValue(row.trailerTemplate()))
        .checksumType(row.checksumType())
        .compressType(row.compressType())
        .encryptType(row.encryptType())
        .namingRule(row.namingRule())
        .fieldMappings(stringValue(row.fieldMappings()))
        .validationRuleSet(stringValue(row.validationRuleSet()))
        .defaultQueryCode(row.defaultQueryCode())
        .defaultQuerySql(row.defaultQuerySql())
        .queryParamSchema(stringValue(row.queryParamSchema()))
        .streamingEnabled(row.streamingEnabled())
        .pageSize(row.pageSize())
        .fetchSize(row.fetchSize())
        .chunkSize(row.chunkSize())
        .previewMaskingEnabled(row.previewMaskingEnabled())
        .errorLineMaskingEnabled(row.errorLineMaskingEnabled())
        .logMaskingEnabled(row.logMaskingEnabled())
        .contentEncryptionEnabled(row.contentEncryptionEnabled())
        .encryptionKeyRef(row.encryptionKeyRef())
        .downloadRequiresApproval(row.downloadRequiresApproval())
        .maskingRuleSet(row.maskingRuleSet())
        .enabled(row.enabled())
        .version(row.version())
        .description(row.description())
        .createdBy(row.createdBy())
        .updatedBy(row.updatedBy())
        .createdAt(row.createdAt())
        .updatedAt(row.updatedAt())
        .build();
  }

  private static String stringValue(Map<String, Object> row, String key) {
    return stringValue(row.get(key));
  }

  private static String stringValue(Object value) {
    return value == null ? null : value.toString();
  }

  private static Long longValue(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value instanceof Number number
        ? number.longValue()
        : value == null ? null : Long.valueOf(value.toString());
  }

  private static Integer intValue(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value instanceof Number number
        ? number.intValue()
        : value == null ? null : Integer.valueOf(value.toString());
  }

  private static Boolean booleanValue(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value instanceof Boolean bool
        ? bool
        : value == null ? null : Boolean.valueOf(value.toString());
  }

  private static Instant instantValue(Map<String, Object> row, String key) {
    Object value = row.get(key);
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof OffsetDateTime dateTime) {
      return dateTime.toInstant();
    }
    if (value instanceof LocalDateTime dateTime) {
      return dateTime.toInstant(ZoneOffset.UTC);
    }
    if (value == null) {
      return null;
    }
    String text = value.toString();
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException ignored) {
      // MyBatis HTTP proxy in integration tests serializes PostgreSQL timestamps without an offset.
      // These configuration timestamps are stored and exposed as UTC, so retain the former wire
      // value.
      String localDateTimeText = text.replace(' ', 'T');
      LocalDateTime localDateTime = LocalDateTime.parse(localDateTimeText);
      return localDateTime.toInstant(ZoneOffset.UTC);
    }
  }
}
