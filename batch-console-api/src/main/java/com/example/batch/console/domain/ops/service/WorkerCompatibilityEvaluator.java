package com.example.batch.console.domain.ops.service;

import com.example.batch.console.domain.ops.dto.SdkPlatformConstants;
import com.example.batch.console.domain.ops.dto.WorkerCompatibility;
import com.example.batch.console.domain.ops.dto.WorkerCompatibility.ReasonCode;
import com.example.batch.console.domain.ops.dto.WorkerCompatibility.Status;
import org.springframework.stereotype.Component;

/**
 * 由 worker 上报的 {@code sdkVersion} 算出协议/SDK 兼容状态(console SDK 运行时可见性 ①)。
 *
 * <p>判定纯只读、无副作用:对照 worker 上报版本主版本号 vs {@link SdkPlatformConstants#CURRENT_SDK_MAJOR}。 仅依据
 * 真实可得字段(sdkVersion),算不出主版本一律 {@link Status#UNKNOWN},不瞎判。
 */
@Component
public class WorkerCompatibilityEvaluator {

  /**
   * 计算单个 worker 的兼容状态。
   *
   * @param reportedSdkVersion worker register 上报的 SDK 库版本(可空,如 {@code "1.1.0"} / {@code
   *     "2.0.0-rc"});非 SDK worker 或老 worker 为 null
   * @return 兼容状态(永不为 null)
   */
  public WorkerCompatibility evaluate(String reportedSdkVersion) {
    Integer major = parseMajor(reportedSdkVersion);
    String platformMajor = "v" + SdkPlatformConstants.CURRENT_SDK_MAJOR;
    if (major == null) {
      return new WorkerCompatibility(
          Status.UNKNOWN, ReasonCode.SDK_VERSION_UNKNOWN, reportedSdkVersion, platformMajor);
    }
    if (major < SdkPlatformConstants.CURRENT_SDK_MAJOR) {
      return new WorkerCompatibility(
          Status.SDK_OUTDATED, ReasonCode.SDK_VERSION_BEHIND, reportedSdkVersion, platformMajor);
    }
    if (major > SdkPlatformConstants.CURRENT_SDK_MAJOR) {
      return new WorkerCompatibility(
          Status.PROTOCOL_UNSUPPORTED,
          ReasonCode.SDK_VERSION_AHEAD,
          reportedSdkVersion,
          platformMajor);
    }
    return new WorkerCompatibility(
        Status.OK, ReasonCode.COMPATIBLE, reportedSdkVersion, platformMajor);
  }

  /**
   * 解析 sdkVersion 的整数主版本(如 {@code "1.1.0"} → 1;{@code "2.0.0-rc"} → 2;{@code "v1.3"} → 1)。
   * 取首段连续数字;空 / 无前导数字 → null(交由调用方判 UNKNOWN)。
   */
  private Integer parseMajor(String sdkVersion) {
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
}
