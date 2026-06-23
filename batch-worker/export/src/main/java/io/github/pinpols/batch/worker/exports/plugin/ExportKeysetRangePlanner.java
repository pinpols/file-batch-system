package io.github.pinpols.batch.worker.exports.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.plugin.ExportDataContext;
import io.github.pinpols.batch.common.utils.PostgresqlJsonbTexts;
import io.github.pinpols.batch.common.utils.Texts;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/** keyset-range 激活判定 + 每分区边界（缓存进 exportSnapshot，只算一次）。任何异常 → INACTIVE（退回 hashtext）。 */
@Slf4j
public class ExportKeysetRangePlanner {

  static final String SNAP_KEY = "__export_keyset_range";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /**
   * @param minMaxSupplier 算游标列 [min,max] 的回调（BigDecimal[2]；非数值/空 → 元素 null 或抛异常）。
   *     仅在激活且首次解析时调用一次；结果（含 INACTIVE）缓存进 exportSnapshot，后续页直接复用。
   */
  ExportKeysetRange resolve(ExportDataContext context, Supplier<BigDecimal[]> minMaxSupplier) {
    Map<String, Object> snap = context.exportSnapshot();
    if (snap != null && snap.get(SNAP_KEY) instanceof ExportKeysetRange cached) {
      return cached;
    }
    ExportKeysetRange resolved = compute(context, minMaxSupplier);
    if (snap != null) {
      snap.put(SNAP_KEY, resolved);
    }
    return resolved;
  }

  private ExportKeysetRange compute(
      ExportDataContext context, Supplier<BigDecimal[]> minMaxSupplier) {
    if (context.partitionCount() <= 1 || !optedIn(context)) {
      return ExportKeysetRange.inactiveFor(context.partitionCount(), context.partitionNo());
    }
    try {
      BigDecimal[] mm = minMaxSupplier.get();
      if (mm == null || mm.length != 2 || mm[0] == null || mm[1] == null) {
        return ExportKeysetRange.inactiveFor(context.partitionCount(), context.partitionNo());
      }
      return ExportKeysetRange.equalWidth(
          mm[0], mm[1], context.partitionCount(), context.partitionNo());
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(ExportKeysetRangePlanner.class, "catch:keysetRangeMinMax", ex);
      return ExportKeysetRange.inactiveFor(context.partitionCount(), context.partitionNo());
    }
  }

  private boolean optedIn(ExportDataContext context) {
    Map<String, Object> tc = context.templateConfig();
    if (tc == null || tc.isEmpty()) {
      return false;
    }
    if (truthy(firstNonNull(tc.get("partition_keyset_range"), tc.get("partitionKeysetRange")))) {
      return true;
    }
    Map<String, Object> qps = toMap(tc.get("query_param_schema"));
    if (truthy(firstNonNull(qps.get("partition_keyset_range"), qps.get("partitionKeysetRange")))) {
      return true;
    }
    Map<String, Object> sqlTemplate =
        toMap(firstNonNull(qps.get("sqlTemplateExport"), qps.get("sql_template_export")));
    return truthy(
        firstNonNull(
            sqlTemplate.get("partitionKeysetRange"), sqlTemplate.get("partition_keyset_range")));
  }

  private static Object firstNonNull(Object... values) {
    for (Object value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static boolean truthy(Object value) {
    return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> toMap(Object raw) {
    if (raw instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, v) -> out.put(String.valueOf(k), v));
      return out;
    }
    String text = raw instanceof String s ? s : PostgresqlJsonbTexts.tryExtract(raw);
    if (Texts.hasText(text)) {
      try {
        return OBJECT_MAPPER.readValue(text, Map.class);
      } catch (Exception ex) {
        SwallowedExceptionLogger.warn(ExportKeysetRangePlanner.class, "catch:jsonbParse", ex);
      }
    }
    return Map.of();
  }
}
