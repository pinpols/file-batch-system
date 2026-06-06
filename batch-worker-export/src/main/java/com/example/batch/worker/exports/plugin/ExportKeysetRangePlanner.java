package com.example.batch.worker.exports.plugin;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.plugin.ExportDataContext;
import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/** keyset-range 激活判定 + 每分区边界（缓存进 exportSnapshot，只算一次）。任何异常 → INACTIVE（退回 hashtext）。 */
@Slf4j
public class ExportKeysetRangePlanner {

  static final String SNAP_KEY = "__export_keyset_range";

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
    Object v = tc == null ? null : tc.get("partition_keyset_range");
    return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
  }
}
