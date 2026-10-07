package io.github.pinpols.batch.sdk.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.pinpols.batch.sdk.dispatcher.TaskDispatchMessage;
import io.github.pinpols.batch.sdk.dispatcher.WorkerRuntimeState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lane P (drift guard): docs/api/sdk-shared-constants.yaml 必须与 Java 源头集合等值。
 *
 * <p>任一边新增 / 删除 / 重命名 → 本测试 fail-fast,推 "请同步 docs/api/sdk-shared-constants.yaml"。
 *
 * <p>受控 keys(本测试强制):
 *
 * <ul>
 *   <li>{@code schema_versions_supported} ← {@link TaskDispatchMessage#SUPPORTED_MAJOR_VERSIONS}
 *   <li>{@code worker_runtime_states} ← {@link WorkerRuntimeState} enum constants
 *   <li>{@code sensitive_keywords} ← platform {@code SensitiveDataValidator}.SENSITIVE_KEYWORDS source
 *   <li>{@code task_statuses} ← platform {@code TaskStatus} enum source
 *   <li>{@code report_error_codes} ← SDK {@code SdkErrorCode} public static final String source
 * </ul>
 *
 * <p>放行 keys(yaml 可有,Java 暂无):{@code atomic_error_codes}(预留 ADR-029,enum 落地后再纳管)。
 *
 * <p>其余语言 SDK 的同名校验:{@code sdk/python/tests/test_shared_constants_parity.py}、{@code
 * sdk/go/protocol/constants_parity_test.go}、{@code
 * sdk/typescript/tests/shared-constants-parity.test.ts}、{@code sdk/rust/tests/constants_parity.rs},
 * 共同锁定 Java、YAML 与四个消费侧语言常量集合的一致性。
 */
@DisplayName("SDK 共享常量 — Java 源头集合与 docs/api 契约文件的双向等值校验")
class SharedConstantsParityTest {

  private static final YAMLMapper YAML = new YAMLMapper();
  private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]+)\"");

  private static Path repoRoot() {
    for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isDirectory(p.resolve("docs/api"))) {
        return p;
      }
    }
    return Paths.get("").toAbsolutePath();
  }

  private static Path yamlPath() {
    return repoRoot().resolve("docs/api/sdk-shared-constants.yaml");
  }

  private static Path sourcePath(String relativePath) {
    return repoRoot().resolve(relativePath);
  }

  @Test
  @DisplayName("调度消息支持的主版本集合与契约文件完全一致")
  void schemaVersionsSupported_match() throws IOException {
    Set<String> java = TaskDispatchMessage.SUPPORTED_MAJOR_VERSIONS;
    Set<String> yaml = readList("schema_versions_supported");
    assertParity("schema_versions_supported", java, yaml);
  }

  @Test
  @DisplayName("worker 运行时状态枚举常量与契约文件完全一致")
  void workerRuntimeStates_match() throws IOException {
    Set<String> java = Arrays.stream(WorkerRuntimeState.values())
        .map(Enum::name)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> yaml = readList("worker_runtime_states");
    assertParity("worker_runtime_states", java, yaml);
  }

  @Test
  @DisplayName("敏感字段关键词清单与契约文件完全一致")
  void sensitiveKeywords_match() throws Exception {
    Set<String> java = readStringListFromSource(
        "batch-common/src/main/java/io/github/pinpols/batch/common/security/SensitiveDataValidator.java",
        "SENSITIVE_KEYWORDS");
    Set<String> yaml = readList("sensitive_keywords");
    assertParity("sensitive_keywords", java, yaml);
  }

  @Test
  @DisplayName("任务状态枚举常量与契约文件完全一致")
  void taskStatuses_match() throws IOException {
    Set<String> java = readEnumConstantsFromSource(
        "batch-common/src/main/java/io/github/pinpols/batch/common/enums/TaskStatus.java",
        "TaskStatus");
    Set<String> yaml = readList("task_statuses");
    assertParity("task_statuses", java, yaml);
  }

  @Test
  @DisplayName("SDK 上报错误码常量与契约文件完全一致")
  void reportErrorCodes_match() throws IOException {
    Set<String> java = readStringConstantsFromSource(
        "sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkErrorCode.java");
    Set<String> yaml = readList("report_error_codes");
    assertParity("report_error_codes", java, yaml);
  }

  /**
   * 抽取某类里所有 {@code public static final String X = "v";} 的值。用于「Java 是源头」的常量类:新增常量会自动进入
   * 断言范围,YAML 未同步即 fail。
   */
  private static Set<String> readStringConstantsFromSource(String relativePath) throws IOException {
    String source = Files.readString(sourcePath(relativePath));
    Matcher constant = Pattern.compile(
            "public\\s+static\\s+final\\s+String\\s+[A-Z][A-Z0-9_]*\\s*=\\s*\"([^\"]+)\";")
        .matcher(source);
    Set<String> values = new LinkedHashSet<>();
    while (constant.find()) {
      values.add(constant.group(1));
    }
    assertThat(values)
        .as("source %s must declare public static final String constants", relativePath)
        .isNotEmpty();
    return values;
  }

  private static Set<String> readList(String key) throws IOException {
    JsonNode root = YAML.readTree(yamlPath().toFile());
    JsonNode arr = root.get(key);
    assertThat(arr).as("yaml missing key %s", key).isNotNull();
    assertThat(arr.isArray()).as("yaml key %s must be array", key).isTrue();
    return StreamSupport.stream(arr.spliterator(), false)
        .map(JsonNode::asText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static Set<String> readStringListFromSource(String relativePath, String fieldName)
      throws IOException {
    String source = Files.readString(sourcePath(relativePath));
    Pattern fieldPattern = Pattern.compile(
        "\\b" + Pattern.quote(fieldName) + "\\b\\s*=\\s*List\\.of\\((.*?)\\);", Pattern.DOTALL);
    Matcher field = fieldPattern.matcher(source);
    assertThat(field.find())
        .as("source missing List.of field %s in %s", fieldName, relativePath)
        .isTrue();

    Matcher literal = STRING_LITERAL.matcher(field.group(1));
    Set<String> values = new LinkedHashSet<>();
    while (literal.find()) {
      values.add(literal.group(1));
    }
    assertThat(values)
        .as("source field %s in %s must not be empty", fieldName, relativePath)
        .isNotEmpty();
    return values;
  }

  private static Set<String> readEnumConstantsFromSource(String relativePath, String enumName)
      throws IOException {
    String source = Files.readString(sourcePath(relativePath));
    int enumIndex = source.indexOf("enum " + enumName);
    assertThat(enumIndex)
        .as("source missing enum %s in %s", enumName, relativePath)
        .isGreaterThanOrEqualTo(0);
    int bodyStart = source.indexOf('{', enumIndex);
    int constantsEnd = source.indexOf(';', bodyStart);
    assertThat(bodyStart)
        .as("source enum %s has no body in %s", enumName, relativePath)
        .isGreaterThanOrEqualTo(0);
    assertThat(constantsEnd)
        .as("source enum %s has no constant terminator in %s", enumName, relativePath)
        .isGreaterThan(bodyStart);

    Matcher constant = Pattern.compile("(?m)^\\s*([A-Z][A-Z0-9_]*)\\s*(?:\\(|,|$)")
        .matcher(source.substring(bodyStart + 1, constantsEnd));
    Set<String> values = new LinkedHashSet<>();
    while (constant.find()) {
      values.add(constant.group(1));
    }
    assertThat(values)
        .as("source enum %s in %s must not be empty", enumName, relativePath)
        .isNotEmpty();
    return values;
  }

  private static void assertParity(String key, Set<String> java, Set<String> yaml) {
    Set<String> onlyInJava = new LinkedHashSet<>(java);
    onlyInJava.removeAll(yaml);
    Set<String> onlyInYaml = new LinkedHashSet<>(yaml);
    onlyInYaml.removeAll(java);
    assertThat(onlyInJava)
        .as(
            "shared-constants 漂移 [%s]:Java 有但 yaml 没有 = %s。请同步 docs/api/sdk-shared-constants.yaml",
            key, onlyInJava)
        .isEmpty();
    assertThat(onlyInYaml)
        .as("shared-constants 漂移 [%s]:yaml 有但 Java 没有 = %s。请从 yaml 删除或在 Java 加上", key, onlyInYaml)
        .isEmpty();
  }
}
