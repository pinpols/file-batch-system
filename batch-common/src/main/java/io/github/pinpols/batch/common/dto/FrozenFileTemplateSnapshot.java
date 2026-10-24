package io.github.pinpols.batch.common.dto;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 受保护的重放载荷携带完整文件模板定义，不作为读取最新配置的提示。 */
public record FrozenFileTemplateSnapshot(
    Integer schemaVersion,
    String tenantId,
    String templateCode,
    String templateType,
    Long templateId,
    Integer templateVersion,
    Map<String, Object> configuration) {

  public static final String PAYLOAD_KEY = "frozenFileTemplateProtected";
  public static final String COLLECTION_KEY = "_frozenFileTemplatesProtected";
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public FrozenFileTemplateSnapshot {
    boolean snapshotFieldsAreInvalid = EmptyChecks.isNull(schemaVersion)
        || schemaVersion != CURRENT_SCHEMA_VERSION
        || EmptyChecks.isBlank(tenantId)
        || EmptyChecks.isBlank(templateCode)
        || EmptyChecks.isBlank(templateType)
        || EmptyChecks.isNull(templateId)
        || templateId <= 0
        || EmptyChecks.isNull(templateVersion)
        || templateVersion <= 0
        || EmptyChecks.isEmpty(configuration);
    if (snapshotFieldsAreInvalid) {
      throw invalid();
    }
    boolean configurationDoesNotMatchSnapshot = !tenantId.equals(configuration.get("tenant_id"))
        || !templateCode.equals(configuration.get("template_code"))
        || !templateType.equals(configuration.get("template_type"))
        || templateId != positiveLong(configuration.get("id"))
        || templateVersion.longValue() != positiveLong(configuration.get("version"));
    if (configurationDoesNotMatchSnapshot) {
      throw invalid();
    }
    configuration = copyObject(configuration);
  }

  public static FrozenFileTemplateSnapshot capture(Map<String, Object> configuration) {
    if (EmptyChecks.isEmpty(configuration)) {
      throw invalid();
    }
    String tenantId = text(configuration.get("tenant_id"));
    String templateCode = text(configuration.get("template_code"));
    String templateType = text(configuration.get("template_type"));
    long templateId = positiveLong(configuration.get("id"));
    long versionNumber = positiveLong(configuration.get("version"));
    int templateVersion = Math.toIntExact(versionNumber);
    return new FrozenFileTemplateSnapshot(
        CURRENT_SCHEMA_VERSION,
        tenantId,
        templateCode,
        templateType,
        templateId,
        templateVersion,
        configuration);
  }

  public void requireIdentity(String tenantId, String templateCode, String templateType) {
    boolean templateSnapshotIdentityDoesNotMatch = !this.tenantId.equals(tenantId)
        || !this.templateCode.equals(templateCode)
        || !this.templateType.equals(templateType);
    if (templateSnapshotIdentityDoesNotMatch) {
      throw invalid();
    }
  }

  @Override
  public String toString() {
    return "FrozenFileTemplateSnapshot[templateId=" + templateId + ", templateVersion="
        + templateVersion + ", configuration=<redacted>]";
  }

  private static String text(Object value) {
    if (!(value instanceof String text)) {
      throw invalid();
    }
    return text;
  }

  private static long positiveLong(Object value) {
    try {
      String numericText = String.valueOf(value);
      BigDecimal exactValue = new BigDecimal(numericText);
      long result = exactValue.longValueExact();
      if (result <= 0) {
        throw invalid();
      }
      return result;
    } catch (ArithmeticException | NumberFormatException invalidNumber) {
      throw invalid();
    }
  }

  private static Map<String, Object> copyObject(Map<?, ?> source) {
    Map<String, Object> copy = new LinkedHashMap<>();
    source.forEach((key, value) -> {
      if (!(key instanceof String text)) {
        throw invalid();
      }
      Object copiedValue = copyValue(value);
      copy.put(text, copiedValue);
    });
    return Collections.unmodifiableMap(copy);
  }

  private static Object copyValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      return copyObject(map);
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      list.forEach(item -> {
        Object copiedItem = copyValue(item);
        copy.add(copiedItem);
      });
      return Collections.unmodifiableList(copy);
    }
    boolean valueIsSupportedImmutableScalar = EmptyChecks.isNull(value)
        || value instanceof String
        || value instanceof Boolean
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigDecimal
        || value instanceof BigInteger
        || value instanceof Double doubleValue && Double.isFinite(doubleValue)
        || value instanceof Float floatValue && Float.isFinite(floatValue);
    if (valueIsSupportedImmutableScalar) {
      return value;
    }
    throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("invalid frozen file template snapshot");
  }
}
