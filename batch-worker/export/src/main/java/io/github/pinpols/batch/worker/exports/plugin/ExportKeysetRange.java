package io.github.pinpols.batch.worker.exports.plugin;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 单个分片的游标值区间 [loN, hiN)（末片 includeUpper=true 时含上界，防丢 max 行）。等宽切分，数值游标列专用。 active=false 表示不启用
 * keyset-range，调用方退回 hashtext；此时仍携带 partitionCount/partitionNo 供 hashtext 谓词使用。
 */
public record ExportKeysetRange(
    boolean active,
    BigDecimal loN,
    BigDecimal hiN,
    boolean includeUpper,
    int partitionCount,
    int partitionNo) {

  static final ExportKeysetRange INACTIVE = new ExportKeysetRange(false, null, null, false, 0, 0);

  static ExportKeysetRange inactiveFor(int partitionCount, int partitionNo) {
    return new ExportKeysetRange(false, null, null, false, partitionCount, partitionNo);
  }

  /**
   * lo/hi 为游标列 min/max；partitionNo 1-based。lo>=hi（单值/空）或 partitionCount<=1 → inactiveFor（退
   * hashtext）。
   */
  static ExportKeysetRange equalWidth(
      BigDecimal lo, BigDecimal hi, int partitionCount, int partitionNo) {
    boolean keysetRangeCannotBePartitioned =
        EmptyChecks.isNull(lo) || EmptyChecks.isNull(hi) || lo.compareTo(hi) >= 0 || partitionCount <= 1;
    if (keysetRangeCannotBePartitioned) {
      return inactiveFor(partitionCount, partitionNo);
    }
    BigDecimal span = hi.subtract(lo);
    long lowerPartitionOffset = partitionNo - 1L;
    BigDecimal lowerPartitionNumerator =
        span.multiply(BigDecimal.valueOf(lowerPartitionOffset));
    BigDecimal lowerPartitionWidth = lowerPartitionNumerator.divide(
        BigDecimal.valueOf(partitionCount), MathContext.DECIMAL64);
    BigDecimal loN = lo.add(lowerPartitionWidth);
    boolean last = partitionNo == partitionCount;
    BigDecimal hiN = hi;
    if (!last) {
      BigDecimal upperPartitionNumerator = span.multiply(BigDecimal.valueOf(partitionNo));
      BigDecimal upperPartitionWidth = upperPartitionNumerator.divide(
          BigDecimal.valueOf(partitionCount), MathContext.DECIMAL64);
      hiN = lo.add(upperPartitionWidth);
    }
    return new ExportKeysetRange(true, loN, hiN, last, partitionCount, partitionNo);
  }
}
