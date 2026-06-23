package io.github.pinpols.batch.console.domain.ops.service;

import io.github.pinpols.batch.console.domain.ops.dto.SdkCatalog;
import io.github.pinpols.batch.console.domain.ops.dto.SdkCatalog.ProtocolVersion;
import io.github.pinpols.batch.console.domain.ops.dto.SdkCatalog.SdkDoc;
import io.github.pinpols.batch.console.domain.ops.dto.SdkCatalog.SdkLanguage;
import io.github.pinpols.batch.console.domain.ops.dto.SdkPlatformConstants;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * SDK 目录静态数据源(开发者门户后端,SDK 运行时可见性 ②)。
 *
 * <p>console-api 不依赖 SDK 模块、运行时不带 {@code docs/} 文件,故以静态镜像维护目录,由 {@code
 * ConsoleSdkCatalogServiceTest} 对照真实 SDK 打包元数据(pyproject / package.json / Cargo.toml / pom)+ {@code
 * sdk-shared-constants.yaml} 做 drift-guard。<b>没有的真数据不编</b>:未发布的语言标 {@code repo-version} / {@code
 * unpublished}。
 */
@Service
public class ConsoleSdkCatalogService {

  /** per-PR parity 强制(BYO conformance 契约 + sdk-contract-parity.yml CI);各语言一致。 */
  private static final String CONFORMANCE_PER_PR_PARITY = "PER_PR_PARITY_ENFORCED";

  /**
   * 各语言 SDK 条目。版本/包名取自真实打包元数据(已核对):
   *
   * <ul>
   *   <li>java {@code io.github.pinpols.batch:batch-worker-sdk} ← pom {@code ${revision}} = 仓库
   *       1.1.0-SNAPSHOT(未发布)
   *   <li>python {@code batch-worker-sdk} ← {@code _version.py} 0.5.0a0(pre-release)
   *   <li>typescript {@code @batch/worker-sdk} ← package.json 1.1.0
   *   <li>rust {@code batch-worker-sdk} ← Cargo.toml 1.1.0
   *   <li>go {@code github.com/pinpols/file-batch-system/batch-worker-sdk-go} ← 无 tag(仓库版)
   * </ul>
   */
  private static final List<SdkLanguage> LANGUAGES =
      List.of(
          new SdkLanguage(
              "java",
              "io.github.pinpols.batch:batch-worker-sdk",
              "1.1.0-SNAPSHOT",
              "<dependency>\n"
                  + "  <groupId>io.github.pinpols.batch</groupId>\n"
                  + "  <artifactId>batch-worker-sdk</artifactId>\n"
                  + "  <version>1.1.0-SNAPSHOT</version>\n"
                  + "</dependency>",
              CONFORMANCE_PER_PR_PARITY),
          new SdkLanguage(
              "python",
              "batch-worker-sdk",
              "0.5.0a0",
              "pip install batch-worker-sdk",
              CONFORMANCE_PER_PR_PARITY),
          new SdkLanguage(
              "typescript",
              "@batch/worker-sdk",
              "1.1.0",
              "npm install @batch/worker-sdk",
              CONFORMANCE_PER_PR_PARITY),
          new SdkLanguage(
              "rust",
              "batch-worker-sdk",
              "1.1.0",
              "cargo add batch-worker-sdk",
              CONFORMANCE_PER_PR_PARITY),
          new SdkLanguage(
              "go",
              "github.com/pinpols/file-batch-system/batch-worker-sdk-go",
              "repo-version",
              "go get github.com/pinpols/file-batch-system/batch-worker-sdk-go",
              CONFORMANCE_PER_PR_PARITY));

  /** SDK 契约文档索引(仓库相对路径)。 */
  private static final List<SdkDoc> DOCS =
      List.of(
          new SdkDoc("BYO SDK Conformance Contract", "docs/sdk/byo-conformance-contract.md"),
          new SdkDoc("SDK Shared Constants", "docs/api/sdk-shared-constants.yaml"),
          new SdkDoc("SDK Wire Protocol", "docs/sdk/wire-protocol.md"));

  /** 组装只读目录(无副作用,可被外层缓存)。 */
  public SdkCatalog catalog() {
    List<String> supported = SdkPlatformConstants.SCHEMA_VERSIONS_SUPPORTED;
    int maxMajor =
        supported.stream()
            .map(v -> v.startsWith("v") ? v.substring(1) : v)
            .mapToInt(Integer::parseInt)
            .max()
            .orElse(0);
    String current = "v" + maxMajor;
    String rejectedFrom = "v" + (maxMajor + 1);
    ProtocolVersion protocol = new ProtocolVersion(supported, current, rejectedFrom);

    Map<String, List<String>> sharedConstants =
        Map.of("schema_versions_supported", SdkPlatformConstants.SCHEMA_VERSIONS_SUPPORTED);

    return new SdkCatalog(protocol, LANGUAGES, sharedConstants, DOCS);
  }
}
