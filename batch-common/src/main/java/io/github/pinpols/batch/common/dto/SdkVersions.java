package io.github.pinpols.batch.common.dto;

/**
 * SDK 库版本(worker 上报的 {@code sdkVersion},如 {@code "1.1.0"} / {@code "2.0.0-rc"} / {@code
 * "v1.3"})的整数主版本解析共享工具。
 *
 * <p>与 {@link SdkProtocolVersions} 同包但分工不同:{@code SdkProtocolVersions} 解析 <b>协议 schema 主版本</b>(归一为
 * {@code "v1"} token,register 协议门禁用);本类解析 <b>SDK 库版本</b>的整数主版本(console 兼容告警 {@code
 * WorkerCompatibilityEvaluator} 与 orchestrator register 的最低 SDK 版本门禁共用),返回 {@code Integer} 便于数值比较。
 *
 * <p>放 {@code batch-common} 是因为 orchestrator(register 最低版本门禁)与 console-api(兼容告警)都要用,而两者互不依赖, 都依赖
 * batch-common。
 */
public final class SdkVersions {

  /**
   * 解析 sdkVersion 的整数主版本(如 {@code "1.1.0"} → 1;{@code "2.0.0-rc"} → 2;{@code "v1.3"} → 1)。
   * 取首段连续数字;空 / 空白 / 无前导数字 → {@code null}(交由调用方判定 UNKNOWN / legacy 放行)。
   */
  public static Integer parseMajor(String sdkVersion) {
    if (sdkVersion == null || sdkVersion.isBlank()) {
      return null;
    }
    String trimmed = sdkVersion.strip();
    int start = 0;
    // 容忍前缀 'v'/'V'(如 "v1.2.0")
    if (trimmed.charAt(0) == 'v' || trimmed.charAt(0) == 'V') {
      start = 1;
    }
    int end = start;
    while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
      end++;
    }
    if (end == start) {
      return null;
    }
    try {
      return Integer.parseInt(trimmed.substring(start, end));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private SdkVersions() {}
}
