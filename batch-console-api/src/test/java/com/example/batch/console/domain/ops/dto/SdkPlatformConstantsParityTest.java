package com.example.batch.console.domain.ops.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Drift-guard:console-api 的 {@link SdkPlatformConstants} 镜像必须与 {@code
 * docs/api/sdk-shared-constants.yaml} 的 {@code schema_versions_supported} 等值。
 *
 * <p>任一边新增 / 删除 / 改值 → 本测试 fail-fast。权威源是 SDK {@code TaskDispatchMessage.SUPPORTED_MAJOR_VERSIONS}
 * → YAML → 本镜像;改值从 SDK 起,YAML 跟,本镜像跟。
 */
class SdkPlatformConstantsParityTest {

  private static Path repoRoot() {
    for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isDirectory(p.resolve("docs/api"))) {
        return p;
      }
    }
    return Paths.get("").toAbsolutePath();
  }

  private static JsonNode loadYaml() throws IOException {
    Path yaml = repoRoot().resolve("docs/api/sdk-shared-constants.yaml");
    return new YAMLMapper().readTree(Files.readString(yaml));
  }

  @Test
  void schemaVersionsSupportedMirrorsYaml() throws IOException {
    JsonNode yamlVersions = loadYaml().get("schema_versions_supported");
    List<String> fromYaml = new ArrayList<>();
    yamlVersions.forEach(n -> fromYaml.add(n.asText()));

    assertThat(SdkPlatformConstants.SCHEMA_VERSIONS_SUPPORTED)
        .as("SdkPlatformConstants.SCHEMA_VERSIONS_SUPPORTED 必须 == sdk-shared-constants.yaml")
        .containsExactlyElementsOf(fromYaml);
  }

  @Test
  void currentSdkMajorIsLowestSupportedMajor() {
    // 平台当前 SDK 主版本应是支持集合里最低的 major(v1, v2 → 1);若 YAML/常量漂移,此断言锚定假设。
    assertThat(SdkPlatformConstants.CURRENT_SDK_MAJOR).isEqualTo(1);
    assertThat(SdkPlatformConstants.SCHEMA_VERSIONS_SUPPORTED).contains("v1");
  }
}
