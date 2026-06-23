package io.github.pinpols.batch.console.domain.ops.dto;

import java.util.List;
import java.util.Map;

/**
 * SDK 目录(开发者门户数据源,SDK 运行时可见性 ②)。
 *
 * <p>{@code GET /api/console/sdk/catalog} 的只读负载,供前端门户渲染。各字段数据源:
 *
 * <ul>
 *   <li>{@code protocolVersion} ← {@link SdkPlatformConstants#SCHEMA_VERSIONS_SUPPORTED}(平台当前支持的协议
 *       major 集,权威 = SDK {@code TaskDispatchMessage.SUPPORTED_MAJOR_VERSIONS})
 *   <li>{@code languages[].latestVersion} ← 各 SDK 打包元数据(package.json / pyproject / Cargo.toml / pom
 *       version);未发布标 {@code repo-version} / {@code unpublished}
 *   <li>{@code sharedConstants} ← {@code docs/api/sdk-shared-constants.yaml} 的关键常量镜像
 *   <li>{@code docs[]} ← 仓库内 SDK 契约文档相对路径
 * </ul>
 *
 * @param protocolVersion 平台协议版本视图
 * @param languages 各语言 SDK 条目
 * @param sharedConstants 跨语言关键常量(键 → 值列表)
 * @param docs 文档索引
 */
public record SdkCatalog(
    ProtocolVersion protocolVersion,
    List<SdkLanguage> languages,
    Map<String, List<String>> sharedConstants,
    List<SdkDoc> docs) {

  /**
   * 协议版本视图。
   *
   * @param supportedMajors 平台当前支持的协议 major 集(如 {@code [v1, v2]})
   * @param current 当前 canonical 协议 major(派单时填的最新版,如 {@code v2})
   * @param rejectedFrom 平台 reject 的最低 major(如 {@code v3} — 含及以上 reject)
   */
  public record ProtocolVersion(
      List<String> supportedMajors, String current, String rejectedFrom) {}

  /**
   * 单语言 SDK 条目。
   *
   * @param lang 语言标识(java / python / typescript / rust / go)
   * @param artifact 包/坐标标识
   * @param latestVersion 最新版本(未发布标 {@code repo-version} / {@code unpublished})
   * @param installSnippet 安装片段(从包元数据推导)
   * @param conformanceStatus 合规状态(BYO conformance 契约)
   */
  public record SdkLanguage(
      String lang,
      String artifact,
      String latestVersion,
      String installSnippet,
      String conformanceStatus) {}

  /**
   * 文档索引项。
   *
   * @param title 标题
   * @param path 仓库相对路径
   */
  public record SdkDoc(String title, String path) {}
}
