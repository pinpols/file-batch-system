package com.example.batch.console.domain.ops.dto;

import com.example.batch.common.dto.SdkProtocolVersions;
import java.util.List;

/**
 * 平台 SDK / 协议常量在 console-api 的只读镜像(SDK 运行时可见性后端支撑)。
 *
 * <p>console-api <b>不依赖</b> {@code batch-worker-sdk}(SDK 是租户自托管发布物,ADR-035),也不在运行时 classpath 带
 * {@code docs/api/sdk-shared-constants.yaml}(该文件只在 repo 的 docs/ 下)。故此处以静态镜像维护跨语言权威集合, 由 {@code
 * SdkPlatformConstantsParityTest} 对照 {@code docs/api/sdk-shared-constants.yaml} fail-fast 防漂移 (对齐
 * SDK Java 侧 {@code SharedConstantsParityTest} 的 drift-guard 纪律)。
 *
 * <p>Authority order(见 YAML 头注释):Java enum → YAML → 其他语言 consume。改值只能从 SDK 的 {@code
 * TaskDispatchMessage.SUPPORTED_MAJOR_VERSIONS} 起,YAML 跟,本镜像跟。
 */
public final class SdkPlatformConstants {

  /**
   * 平台当前支持的协议 schema 主版本集合(镜像 {@code sdk-shared-constants.yaml} 的 {@code
   * schema_versions_supported};权威源 = SDK {@code TaskDispatchMessage.SUPPORTED_MAJOR_VERSIONS})。v3+
   * 平台 reject。
   *
   * <p>2026-06-17:服务端字面量收敛到 {@code batch-common} 的 {@link
   * SdkProtocolVersions#SUPPORTED_MAJOR_VERSIONS} —— register
   * 准入门禁(orchestrator)与本只读镜像(console-api)共用同一处常量,消除服务端双源。两侧各自的 sdk-shared-constants.yaml parity
   * 测试仍独立 fail-fast 防漂移。
   */
  public static final List<String> SCHEMA_VERSIONS_SUPPORTED =
      SdkProtocolVersions.SUPPORTED_MAJOR_VERSIONS;

  /**
   * 平台当前 SDK 主版本(整数)。SDK 库版本(java/typescript/rust 跟根 {@code revision} 1.1.x)的主版本 = 1。worker 上报的
   * {@code sdkVersion} 主版本与此对照算兼容。
   */
  public static final int CURRENT_SDK_MAJOR = 1;

  private SdkPlatformConstants() {}
}
