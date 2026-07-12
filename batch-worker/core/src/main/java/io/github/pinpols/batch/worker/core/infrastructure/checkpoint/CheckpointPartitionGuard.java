package io.github.pinpols.batch.worker.core.infrastructure.checkpoint;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * ADR-038 续跑 / 阶段跳过的多分区降级判定(P2:fail-open → fail-closed 收口)。
 *
 * <p>多分区任务(mod 切片 / ADR-046 bundle 展开)K 个 partition 共享同一 {@code pipeline_instance},位点 / step_run 会跨
 * partition 交叉读写 → 续跑跳错行、跳过整段、markCompleted 互判 —— 数据正确性风险。故 {@code PARTITION_COUNT > 1} 必须整体降级为不续跑
 * / 不跳过。
 *
 * <p><b>缺失 vs 非法的区分(护数据正确性)</b>:
 *
 * <ul>
 *   <li><b>缺失</b>(attribute 为 {@code null}):{@code DefaultTaskExecutionWrapper} 仅在 {@code
 *       task.getPartitionCount() != null} 时才写该 attribute,故缺失 = 非分区任务 = 单分区,<b>放行</b>续跑(这是绝大多数任务的常态,
 *       若缺失也拒跑会把续跑特性对所有非分区任务整体废掉)。
 *   <li><b>present 但非法</b>(非数字 / {@code <= 0}):值本应是 orchestrator 下发的正整数;present 却解析不出正整数 = 上游数据损坏,
 *       无法判定分区拓扑 → <b>fail-closed 降级</b>(按多分区处理,不续跑),宁可多做全量重跑也不冒交叉读写损数据的险。
 * </ul>
 */
public final class CheckpointPartitionGuard {

  private CheckpointPartitionGuard() {}

  /**
   * 是否应因分区拓扑降级(不续跑 / 不跳过)。
   *
   * @param rawPartitionCount attributes 里的 {@code PARTITION_COUNT} 原值(可能为 null / Number / String)
   * @return true = 降级(多分区或值非法);false = 放行(缺失或明确单分区)
   */
  public static boolean shouldDegrade(Object rawPartitionCount) {
    if (rawPartitionCount == null) {
      // 缺失 = 非分区任务 = 单分区,放行。
      return false;
    }
    Long parsed = parseLongOrNull(rawPartitionCount);
    if (parsed == null || parsed < 1L) {
      // present 但非法(非数字 / <=0):拓扑不可判定 → fail-closed 降级。
      return true;
    }
    return parsed > 1L;
  }

  private static Long parseLongOrNull(Object value) {
    if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
      return ((Number) value).longValue();
    }
    if (value instanceof Long number) {
      return number;
    }
    if (value instanceof BigInteger number) {
      try {
        return number.longValueExact();
      } catch (ArithmeticException ex) {
        return null;
      }
    }
    if (value instanceof BigDecimal number) {
      try {
        return number.longValueExact();
      } catch (ArithmeticException ex) {
        return null;
      }
    }
    if (value instanceof Float || value instanceof Double) {
      double number = ((Number) value).doubleValue();
      if (!Double.isFinite(number)) {
        return null;
      }
      try {
        return BigDecimal.valueOf(number).longValueExact();
      } catch (ArithmeticException ex) {
        return null;
      }
    }
    String text = String.valueOf(value).trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException ex) {
      return null;
    }
  }
}
