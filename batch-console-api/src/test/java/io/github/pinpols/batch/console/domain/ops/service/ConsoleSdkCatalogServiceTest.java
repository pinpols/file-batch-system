package io.github.pinpols.batch.console.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.ops.dto.SdkCatalog;
import io.github.pinpols.batch.console.domain.ops.dto.SdkCatalog.SdkLanguage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** SDK 运行时可见性 ②:验证 catalog 结构 + 各语言版本对照真实打包元数据(drift-guard)。 */
class ConsoleSdkCatalogServiceTest {

  private final ConsoleSdkCatalogService service = new ConsoleSdkCatalogService();

  private static Path repoRoot() {
    for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isDirectory(p.resolve("docs/api")) && Files.isDirectory(p.resolve("sdk"))) {
        return p;
      }
    }
    return Paths.get("").toAbsolutePath();
  }

  private Map<String, SdkLanguage> languagesByLang() {
    return service.catalog().languages().stream()
        .collect(Collectors.toMap(SdkLanguage::lang, l -> l));
  }

  @Test
  void protocolVersionFromSupportedMajors() {
    SdkCatalog.ProtocolVersion protocol = service.catalog().protocolVersion();

    assertThat(protocol.supportedMajors()).containsExactly("v1", "v2");
    assertThat(protocol.current()).isEqualTo("v2");
    assertThat(protocol.rejectedFrom()).isEqualTo("v3");
  }

  @Test
  void sharedConstantsExposeSchemaVersions() {
    assertThat(service.catalog().sharedConstants())
        .containsEntry("schema_versions_supported", List.of("v1", "v2"));
  }

  @Test
  void docsIndexNonEmptyAndPointsAtRealFiles() {
    assertThat(service.catalog().docs())
        .isNotEmpty()
        .allSatisfy(
            d -> {
              assertThat(d.title()).isNotBlank();
              assertThat(Files.exists(repoRoot().resolve(d.path())))
                  .as("doc path 必须指向真实文件: %s", d.path())
                  .isTrue();
            });
  }

  @Test
  void allFiveLanguagesPresent() {
    assertThat(languagesByLang().keySet())
        .containsExactlyInAnyOrder("java", "python", "typescript", "rust", "go");
    assertThat(service.catalog().languages())
        .allSatisfy(
            l -> {
              assertThat(l.artifact()).isNotBlank();
              assertThat(l.latestVersion()).isNotBlank();
              assertThat(l.installSnippet()).isNotBlank();
              assertThat(l.conformanceStatus()).isEqualTo("PER_PR_PARITY_ENFORCED");
            });
  }

  @Test
  void pythonVersionMatchesVersionPy() throws IOException {
    Path versionPy = repoRoot().resolve("sdk/python/src/batch_worker_sdk/_version.py");
    String actual =
        extract(Files.readString(versionPy), "__version__:\\s*str\\s*=\\s*\"([^\"]+)\"");
    assertThat(languagesByLang().get("python").latestVersion()).isEqualTo(actual);
  }

  @Test
  void typescriptVersionMatchesPackageJson() throws IOException {
    Path pkg = repoRoot().resolve("sdk/typescript/package.json");
    String actual = extract(Files.readString(pkg), "\"version\"\\s*:\\s*\"([^\"]+)\"");
    assertThat(languagesByLang().get("typescript").latestVersion()).isEqualTo(actual);
  }

  @Test
  void rustVersionMatchesCargoToml() throws IOException {
    Path cargo = repoRoot().resolve("sdk/rust/Cargo.toml");
    String actual = extract(Files.readString(cargo), "(?m)^version\\s*=\\s*\"([^\"]+)\"");
    assertThat(languagesByLang().get("rust").latestVersion()).isEqualTo(actual);
  }

  private static String extract(String text, String regex) {
    Matcher m = Pattern.compile(regex).matcher(text);
    assertThat(m.find()).as("regex 必须命中: %s", regex).isTrue();
    return m.group(1);
  }
}
