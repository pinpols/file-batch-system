package io.github.pinpols.batch.common.dto;

import io.github.pinpols.batch.common.enums.DispatchTaskKeys;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 为单次 Job 尝试冻结完整分发通道行，并在持久化前保护内容。 */
public record FrozenDispatchChannelSnapshot(
    Integer schemaVersion,
    String tenantId,
    String channelCode,
    Long channelId,
    Map<String, Object> configuration) {

  public static final String COLLECTION_KEY = DispatchTaskKeys.FROZEN_CHANNEL_SNAPSHOTS;
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public FrozenDispatchChannelSnapshot {
    boolean snapshotFieldsAreInvalid = EmptyChecks.isNull(schemaVersion)
        || schemaVersion != CURRENT_SCHEMA_VERSION
        || EmptyChecks.isBlank(tenantId)
        || EmptyChecks.isBlank(channelCode)
        || EmptyChecks.isNull(channelId)
        || channelId <= 0
        || EmptyChecks.isEmpty(configuration);
    if (snapshotFieldsAreInvalid) {
      throw invalid();
    }
    if (!tenantId.equals(configuration.get("tenant_id"))) {
      throw invalid();
    }
    if (!channelCode.equals(configuration.get("channel_code"))) {
      throw invalid();
    }
    long configurationChannelId = positiveLong(configuration.get("id"));
    if (channelId != configurationChannelId) {
      throw invalid();
    }
    String configurationChannelType = text(configuration.get("channel_type"));
    if (EmptyChecks.isBlank(configurationChannelType)) {
      throw invalid();
    }
    if (!(configuration.get("config_json") instanceof Map<?, ?>)) {
      throw invalid();
    }
    if (Boolean.TRUE.equals(configuration.get("is_deleted"))) {
      throw invalid();
    }
    configuration = copyObject(configuration);
  }

  public static FrozenDispatchChannelSnapshot capture(Map<String, Object> configuration) {
    if (EmptyChecks.isEmpty(configuration)) {
      throw invalid();
    }
    String tenantId = text(configuration.get("tenant_id"));
    String channelCode = text(configuration.get("channel_code"));
    long channelId = positiveLong(configuration.get("id"));
    return new FrozenDispatchChannelSnapshot(
        CURRENT_SCHEMA_VERSION, tenantId, channelCode, channelId, configuration);
  }

  public void requireIdentity(String tenantId, String channelCode) {
    if (!this.tenantId.equals(tenantId) || !this.channelCode.equals(channelCode)) {
      throw invalid();
    }
  }

  public String channelType() {
    return text(configuration.get("channel_type"));
  }

  public boolean enabled() {
    return Boolean.TRUE.equals(configuration.get("enabled"));
  }

  @Override
  public String toString() {
    return "FrozenDispatchChannelSnapshot[channelId=" + channelId + ", channelCode=" + channelCode
        + ", configuration=<redacted>]";
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
      BigDecimal numericValue = new BigDecimal(numericText);
      long result = numericValue.longValueExact();
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
      if (value instanceof Map<?, ?> nested) {
        Map<String, Object> copiedObject = copyObject(nested);
        copy.put(text, copiedObject);
      } else if (value instanceof List<?> list) {
        List<Object> copiedList = copyList(list);
        copy.put(text, copiedList);
      } else {
        Object copiedScalar = copyScalar(value);
        copy.put(text, copiedScalar);
      }
    });
    return Collections.unmodifiableMap(copy);
  }

  private static List<Object> copyList(List<?> values) {
    List<Object> copy = new ArrayList<>(values.size());
    for (Object value : values) {
      if (value instanceof Map<?, ?> nested) {
        Map<String, Object> copiedObject = copyObject(nested);
        copy.add(copiedObject);
      } else if (value instanceof List<?> list) {
        List<Object> copiedList = copyList(list);
        copy.add(copiedList);
      } else {
        Object copiedScalar = copyScalar(value);
        copy.add(copiedScalar);
      }
    }
    return Collections.unmodifiableList(copy);
  }

  private static Object copyScalar(Object value) {
    if (EmptyChecks.isNull(value)
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean) {
      return value;
    }
    throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("invalid frozen dispatch channel snapshot");
  }
}
