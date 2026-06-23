package io.github.pinpols.batch.console.domain.ops.dto;

/**
 * Worker 协议/SDK 兼容状态(console SDK 运行时可见性 ①)。
 *
 * <p>由后端按 worker 上报的 {@code sdkVersion} 对照平台当前支持的 SDK 主版本算出,供 console 标出"该 worker 在旧
 * SDK,建议升级",而不必等它真收到 v-mismatch 才挂。
 *
 * <p><b>数据可得性边界(诚实判定)</b>:worker register 仅上报 {@code sdkVersion}(SDK 库版本字符串,如 {@code
 * "1.1.0"});{@code worker_registry} <b>不存协议 schemaVersion / protocol major</b>(协议 major 是消息维度,不是
 * worker 维度)。因此本兼容判定只基于 {@code sdkVersion} 的主版本 vs 平台当前 SDK 主版本:
 *
 * <ul>
 *   <li>{@code sdkVersion} 为空 / 解析不出主版本 → {@link Status#UNKNOWN}(不瞎判)
 *   <li>主版本 &lt; 平台当前 → {@link Status#SDK_OUTDATED}(旧 SDK,建议升级)
 *   <li>主版本 &gt; 平台当前 → {@link Status#PROTOCOL_UNSUPPORTED}(worker 跑在平台尚不支持的更新主线)
 *   <li>否则 → {@link Status#OK}
 * </ul>
 *
 * @param status 兼容状态枚举
 * @param reasonCode 稳定的原因码(FE 据此 i18n 渲染一句提示;非 BizException error key)
 * @param reportedSdkVersion worker 上报的原始 sdkVersion(可空,便于 FE 直接展示)
 * @param platformSdkMajor 平台当前 SDK 主版本(用于 FE 展示"建议升级到")
 */
public record WorkerCompatibility(
    Status status, String reasonCode, String reportedSdkVersion, String platformSdkMajor) {

  /** 兼容状态枚举(对外稳定,FE 据此着色/排序)。 */
  public enum Status {
    OK,
    SDK_OUTDATED,
    PROTOCOL_UNSUPPORTED,
    UNKNOWN
  }

  /** 稳定原因码常量(FE i18n key 前缀 {@code worker.compatibility.reason.*})。 */
  public static final class ReasonCode {
    public static final String COMPATIBLE = "COMPATIBLE";
    public static final String SDK_VERSION_BEHIND = "SDK_VERSION_BEHIND";
    public static final String SDK_VERSION_AHEAD = "SDK_VERSION_AHEAD";
    public static final String SDK_VERSION_UNKNOWN = "SDK_VERSION_UNKNOWN";

    private ReasonCode() {}
  }
}
