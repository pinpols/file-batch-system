package io.github.pinpols.batch.console.domain.ops.service;

import io.github.pinpols.batch.common.dto.SdkVersions;
import io.github.pinpols.batch.console.domain.ops.dto.SdkPlatformConstants;
import io.github.pinpols.batch.console.domain.ops.dto.WorkerCompatibility;
import io.github.pinpols.batch.console.domain.ops.dto.WorkerCompatibility.ReasonCode;
import io.github.pinpols.batch.console.domain.ops.dto.WorkerCompatibility.Status;
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
    Integer major = SdkVersions.parseMajor(reportedSdkVersion);
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
}
