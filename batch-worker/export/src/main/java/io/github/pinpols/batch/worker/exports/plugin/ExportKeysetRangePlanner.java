package io.github.pinpols.batch.worker.exports.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.plugin.ExportDataContext;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.PostgresqlJsonbTexts;
import io.github.pinpols.batch.common.utils.Texts;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/** 导出分片规划：多分片暂统一使用 hashtext，保留原区间规划代码供后续设计共享计划。 */
@Slf4j
public class ExportKeysetRangePlanner {

  static final String SNAP_KEY = "__export_keyset_range";
  private static final ObjectMapper OBJECT_MAPPER = JsonUtils.newDefaultMapper();

  /**
   * @param minMaxSupplier 既有区间边界探测回调；多分片禁用区间时不会调用。
   */
  ExportKeysetRange resolve(ExportDataContext context, Supplier<BigDecimal[]> minMaxSupplier) {
    // 独立范围探测无法保证所有分片使用同一方案，暂统一哈希，避免重叠或遗漏。
    if (context.partitionCount() > 1) {
      return ExportKeysetRange.inactiveFor(context.partitionCount(), context.partitionNo());
    }
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
    boolean keysetRangeIsNotApplicable = context.partitionCount() <= 1 || !optedIn(context);
    if (keysetRangeIsNotApplicable) {
      return ExportKeysetRange.inactiveFor(context.partitionCount(), context.partitionNo());
    }
    try {
      BigDecimal[] mm = minMaxSupplier.get();
      boolean minMaxBoundsAreIncomplete = EmptyChecks.isNull(mm)
          || mm.length != 2
          || EmptyChecks.isNull(mm[0])
          || EmptyChecks.isNull(mm[1]);
      if (minMaxBoundsAreIncomplete) {
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
    Object templateRangeSetting =
        firstNonNull(tc.get("partition_keyset_range"), tc.get("partitionKeysetRange"));
    if (truthy(templateRangeSetting)) {
      return true;
    }
    Map<String, Object> qps = toMap(tc.get("query_param_schema"));
    Object querySchemaRangeSetting =
        firstNonNull(qps.get("partition_keyset_range"), qps.get("partitionKeysetRange"));
    if (truthy(querySchemaRangeSetting)) {
      return true;
    }
    Object camelCaseSqlTemplate = qps.get("sqlTemplateExport");
    Object snakeCaseSqlTemplate = qps.get("sql_template_export");
    Object sqlTemplateConfig = firstNonNull(camelCaseSqlTemplate, snakeCaseSqlTemplate);
    Map<String, Object> sqlTemplate = toMap(sqlTemplateConfig);
    Object camelCaseRangeSetting = sqlTemplate.get("partitionKeysetRange");
    Object snakeCaseRangeSetting = sqlTemplate.get("partition_keyset_range");
    Object sqlTemplateRangeSetting = firstNonNull(camelCaseRangeSetting, snakeCaseRangeSetting);
    return truthy(sqlTemplateRangeSetting);
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
