package com.example.batch.e2e.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * R3-9 守护:4 个 pipeline worker(import/export/process/dispatch)的 application.yml 必须显式 opt-in {@code
 * batch.worker.atomic.isolation-check.enabled=true},否则 ADR-029 的 classpath 隔离守护 (PR #252-K4 {@code
 * PipelineWorkerAtomicClasspathCheck} 默认 opt-in)失效,pipeline worker 一旦误引 batch-worker-atomic
 * 模块依赖,启动期不会 fail-fast,dual-use executor 类(shell / sql / stored-proc / http)会与 pipeline worker 同
 * classpath,绕过 ADR-029 的 RCE 隔离边界。
 *
 * <p>放 batch-e2e-tests 因为它通过 Maven 依赖了 4 个 pipeline worker 模块 + worker-atomic,跑在 file system 上读
 * {@code ../batch-worker-<type>/src/main/resources/application.yml}(各 worker 模块源码), 不依赖 jar 资源解析,避免
 * classpath 内 application.yml 重名歧义。
 *
 * <p>失败消息要求引用 ADR-029 + 给出 yml 绝对路径,运维 / 评审者直接能定位漏配点。
 */
class PipelineWorkerAtomicIsolationOptInArchTest {

  /** 期望 key 路径:batch.worker.atomic.isolation-check.enabled。 */
  private static final String[] EXPECTED_KEY_PATH = {
    "batch", "worker", "atomic", "isolation-check", "enabled"
  };

  /** 定位 worker 模块根:e2e 模块同级目录(父目录下的 sibling)。 */
  private static Path workerApplicationYml(String module) {
    // e2e 测试 cwd = batch-e2e-tests/,父目录是 reactor 根。
    Path projectRoot = Paths.get("").toAbsolutePath().getParent();
    return projectRoot.resolve(module).resolve("src/main/resources/application.yml");
  }

  @Test
  @DisplayName("ADR-029 守护:batch-worker-import application.yml 必须显式 opt-in isolation-check")
  void importWorkerOptsIn() {
    assertOptIn("batch-worker-import");
  }

  @Test
  @DisplayName("ADR-029 守护:batch-worker-export application.yml 必须显式 opt-in isolation-check")
  void exportWorkerOptsIn() {
    assertOptIn("batch-worker-export");
  }

  @Test
  @DisplayName("ADR-029 守护:batch-worker-process application.yml 必须显式 opt-in isolation-check")
  void processWorkerOptsIn() {
    assertOptIn("batch-worker-process");
  }

  @Test
  @DisplayName("ADR-029 守护:batch-worker-dispatch application.yml 必须显式 opt-in isolation-check")
  void dispatchWorkerOptsIn() {
    assertOptIn("batch-worker-dispatch");
  }

  /** 单独断言一个 module。 */
  private void assertOptIn(String module) {
    Path yml = workerApplicationYml(module);
    assertThat(yml)
        .as(
            "[ADR-029 / PR #252-K4] %s 未找到 application.yml:%s。"
                + "若 worker 模块改名或挪了 application.yml 位置,请同步更新本守护测试。",
            module, yml)
        .exists();

    Map<String, Object> root = loadYaml(yml);
    Object actual = walk(root, EXPECTED_KEY_PATH);

    assertThat(actual)
        .as(
            "[ADR-029] %s 必须在 %s 显式声明 batch.worker.atomic.isolation-check.enabled=true,"
                + "否则 PipelineWorkerAtomicClasspathCheck(默认 opt-in)失效,"
                + "pipeline worker 误引 batch-worker-atomic 依赖时不会 fail-fast。"
                + "请在 application.yml 加:\n"
                + "  batch:\n"
                + "    worker:\n"
                + "      atomic:\n"
                + "        isolation-check:\n"
                + "          enabled: true\n",
            module, yml)
        .isEqualTo(Boolean.TRUE);
  }

  /** 按 key 路径深度优先取值;任一层不存在或非 Map 即返回 {@code null}。 */
  @SuppressWarnings("unchecked")
  private static Object walk(Map<String, Object> root, String... path) {
    Object cursor = root;
    for (String segment : path) {
      if (!(cursor instanceof Map<?, ?> map)) {
        return null;
      }
      cursor = ((Map<String, Object>) map).get(segment);
      if (cursor == null) {
        return null;
      }
    }
    return cursor;
  }

  private static Map<String, Object> loadYaml(Path file) {
    try (var in = Files.newInputStream(file)) {
      Yaml yaml = new Yaml();
      Object loaded = yaml.load(in);
      if (loaded instanceof Map<?, ?> map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
      }
      throw new IllegalStateException("application.yml 根节点不是 Map(可能为空文件或纯 list):" + file);
    } catch (IOException e) {
      throw new IllegalStateException("读取 application.yml 失败:" + file, e);
    }
  }
}
