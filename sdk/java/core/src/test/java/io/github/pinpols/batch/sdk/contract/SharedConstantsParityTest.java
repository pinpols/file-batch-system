package io.github.pinpols.batch.sdk.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.security.SensitiveDataValidator;
import io.github.pinpols.batch.sdk.dispatcher.TaskDispatchMessage;
import io.github.pinpols.batch.sdk.dispatcher.WorkerRuntimeState;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
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
 *   <li>{@code sensitive_keywords} ← {@link SensitiveDataValidator}.SENSITIVE_KEYWORDS
 *   <li>{@code task_statuses} ← {@link TaskStatus} enum constants
 * </ul>
 *
 * <p>放行 keys(yaml 可有,Java 暂无):{@code atomic_error_codes}(预留 ADR-029,enum 落地后再纳管)。
 *
 * <p>Python 侧的对应校验由 Lane Q follow-up 实现(目前 sdk-python/tests/test_shared_constants_parity.py 仅
 * stub)。
 */
class SharedConstantsParityTest {

  private static final YAMLMapper YAML = new YAMLMapper();

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

  @Test
  void schemaVersionsSupported_match() throws IOException {
    Set<String> java = TaskDispatchMessage.SUPPORTED_MAJOR_VERSIONS;
    Set<String> yaml = readList("schema_versions_supported");
    assertParity("schema_versions_supported", java, yaml);
  }

  @Test
  void workerRuntimeStates_match() throws IOException {
    Set<String> java =
        Arrays.stream(WorkerRuntimeState.values())
            .map(Enum::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> yaml = readList("worker_runtime_states");
    assertParity("worker_runtime_states", java, yaml);
  }

  @Test
  void sensitiveKeywords_match() throws Exception {
    // SENSITIVE_KEYWORDS is package-private; reflect to keep test code clean of @SuppressWarnings
    Field f = SensitiveDataValidator.class.getDeclaredField("SENSITIVE_KEYWORDS");
    f.setAccessible(true);
    @SuppressWarnings("unchecked")
    List<String> raw = (List<String>) f.get(null);
    Set<String> java = new LinkedHashSet<>(raw);
    Set<String> yaml = readList("sensitive_keywords");
    assertParity("sensitive_keywords", java, yaml);
  }

  @Test
  void taskStatuses_match() throws IOException {
    Set<String> java =
        Arrays.stream(TaskStatus.values())
            .map(Enum::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> yaml = readList("task_statuses");
    assertParity("task_statuses", java, yaml);
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
