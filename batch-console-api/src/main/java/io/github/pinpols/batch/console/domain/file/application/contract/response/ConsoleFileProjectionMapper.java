package io.github.pinpols.batch.console.domain.file.application.contract.response;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.SecretMasking;
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
    return new ConsoleFileChannelResponse(
        id,
        tenantId,
        channelCode,
        channelName,
        channelType,
        maskedTargetEndpoint,
        authType,
        configJson,
        receiptPolicy,
        timeoutSeconds,
        enabled,
        createdAt,
        updatedAt);
  }

  private static String safeMaskedConfigJson(Map<String, Object> row) {
    try {
      return SecretMasking.maskSensitiveJson(stringValue(row, "config_json"));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  public static ConsoleFileTemplateResponse template(Map<String, Object> row) {
    return row == null
        ? null
        : new ConsoleFileTemplateResponse(
            longValue(row, "id"),
            stringValue(row, "tenant_id"),
            stringValue(row, "template_code"),
            stringValue(row, "template_name"),
            stringValue(row, "template_type"),
            stringValue(row, "biz_type"),
            stringValue(row, "file_format_type"),
            stringValue(row, "charset"),
            stringValue(row, "target_charset"),
            booleanValue(row, "with_bom"),
            stringValue(row, "line_separator"),
            stringValue(row, "delimiter"),
            stringValue(row, "quote_char"),
            stringValue(row, "escape_char"),
            intValue(row, "record_length"),
            intValue(row, "header_rows"),
            intValue(row, "footer_rows"),
            stringValue(row, "header_template"),
            stringValue(row, "trailer_template"),
            stringValue(row, "checksum_type"),
            stringValue(row, "compress_type"),
            stringValue(row, "encrypt_type"),
            stringValue(row, "naming_rule"),
            stringValue(row, "field_mappings"),
            stringValue(row, "validation_rule_set"),
            stringValue(row, "default_query_code"),
            stringValue(row, "default_query_sql"),
            stringValue(row, "query_param_schema"),
            booleanValue(row, "streaming_enabled"),
            intValue(row, "page_size"),
            intValue(row, "fetch_size"),
            intValue(row, "chunk_size"),
            booleanValue(row, "preview_masking_enabled"),
            booleanValue(row, "error_line_masking_enabled"),
            booleanValue(row, "log_masking_enabled"),
            booleanValue(row, "content_encryption_enabled"),
            stringValue(row, "encryption_key_ref"),
            booleanValue(row, "download_requires_approval"),
            stringValue(row, "masking_rule_set"),
            booleanValue(row, "enabled"),
            intValue(row, "version"),
            stringValue(row, "description"),
            stringValue(row, "created_by"),
            stringValue(row, "updated_by"),
            instantValue(row, "created_at"),
            instantValue(row, "updated_at"));
  }

  private static String stringValue(Map<String, Object> row, String key) {
    Object value = row.get(key);
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
