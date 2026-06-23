package io.github.pinpols.batch.common.dto;

import java.util.List;

/**
 * 平台服务端支持的 SDK 协议 schema 主版本集合 + 解析工具(register 准入门禁权威源)。
 *
 * <p>语义:worker 在 {@code POST /internal/workers/register} 携带 {@code protocolVersion}(如 {@code "v1"}
 * / {@code "v2"}),平台据此判定能否接纳。这是 SDK 派单契约 {@code schemaVersion} 的 register 侧对偶 —— 派单时平台填
 * schemaVersion、SDK 校验主版本;注册时 SDK 报 protocolVersion、平台校验主版本。两端共用同一支持集合。
 *
 * <p>放在 {@code batch-common} 而非 {@code batch-console-api} 的原因:register 门禁在 {@code
 * batch-orchestrator} 执行,而 orchestrator <b>不依赖</b> console-api;console-api 的 {@code
 * SdkPlatformConstants} 转而委托本类,使服务端 只有一处字面量(由各自的 {@code sdk-shared-constants.yaml} parity 测试
 * fail-fast 防漂移)。
 *
 * <p>权威链:SDK {@code TaskDispatchMessage.SUPPORTED_MAJOR_VERSIONS} → {@code
 * docs/api/sdk-shared-constants.yaml} 的 {@code schema_versions_supported} → 本类。改值从 SDK 起,YAML
 * 跟,本类跟。
 */
public final class SdkProtocolVersions {

  /** 平台当前支持的协议 schema 主版本(规范形如 {@code "v1"} / {@code "v2"});v3+ 平台 register 拒绝。 */
  public static final List<String> SUPPORTED_MAJOR_VERSIONS = List.of("v1", "v2");

  /**
   * 把上报的 protocolVersion 归一化为规范主版本 token(如 {@code "v2-rc"} → {@code "v2"};{@code "1.3.0"} → {@code
   * "v1"};{@code "V2"} → {@code "v2"})。空 / 无前导数字一律返回 {@code null}(交由调用方判定 legacy / 非法)。
   */
  public static String normalizeMajor(String protocolVersion) {
    if (protocolVersion == null || protocolVersion.isBlank()) {
      return null;
    }
    String trimmed = protocolVersion.strip();
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
    return "v" + trimmed.substring(start, end);
  }

  /**
   * 判定上报的 protocolVersion 主版本是否在平台支持集合内。{@code null} / 空 / 无法解析的版本一律返回 {@code false}(由 register
   * 门禁区分:缺字段=legacy 放行、present-but-unsupported=拒绝)。
   */
  public static boolean isSupportedMajor(String protocolVersion) {
    String major = normalizeMajor(protocolVersion);
    return major != null && SUPPORTED_MAJOR_VERSIONS.contains(major);
  }

  private SdkProtocolVersions() {}
}
