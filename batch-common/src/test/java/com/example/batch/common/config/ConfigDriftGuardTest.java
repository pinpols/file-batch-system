package com.example.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * ADR-029 守护测试：服务模块的 application.yml 只能 overlay 共享基线，不能复刻基线已经写过的 "唯一来源"键。
 *
 * <p>判定规则：把 {@code batch-config-defaults/.../batch-defaults.yml} 加载为 flat-key 视图 （{@code
 * spring.kafka.bootstrap-servers} 之类），再对每个服务模块的 application.yml 做同样 展平；若有 key 在两侧同时出现，且该 key
 * 属于"OWNED_KEYS"（基线明确负责的项），则视为 drift 失败。模块独有键（端口、application.name、模块 properties）不受约束。
 *
 * <p>豁免：当模块 overlay 是为了走 module-specific env var（如各 worker 的 hikari pool size） 时，对应 key 不进
 * OWNED_KEYS。每加一个白名单需在 ADR-029 留 PR 痕迹。
 */
class ConfigDriftGuardTest {

  /** 基线"唯一来源"的键集合：这些键如果在模块 application.yml 里重新出现就算 drift。 */
  private static final Set<String> OWNED_KEYS =
      Set.of(
          // 全栈 UTF-8 / 优雅停机
          "server.shutdown",
          "server.servlet.encoding.charset",
          "server.servlet.encoding.force",
          "spring.messages.encoding",
          "spring.jackson.time-zone",
          // 平台 DB 连接元数据（驱动 / URL / 凭据；pool 配置不在此处由模块各自控制）
          "spring.datasource.driver-class-name",
          // Kafka 集群地址与生产者协议
          "spring.kafka.bootstrap-servers",
          "spring.kafka.producer.acks",
          "spring.kafka.listener.ack-mode",
          "spring.kafka.consumer.auto-offset-reset",
          // Redis 通用项
          "spring.data.redis.repositories.enabled",
          "spring.data.redis.host",
          "spring.data.redis.port",
          // MyBatis 公共扫描路径
          "mybatis.mapper-locations",
          // Actuator 通用
          "management.endpoint.health.show-details",
          "management.health.db.enabled",
          "management.health.redis.enabled",
          "management.health.kafka.enabled",
          // batch.* 共享键
          "batch.timezone.default-zone",
          "batch.orchestrator.base-url",
          "batch.storage.minio.endpoint",
          "batch.security.internal-secret",
          "batch.mq.topics.task-result",
          "batch.mq.topics.task-retry",
          "batch.mq.topics.dead-letter");

  private static Path repoRoot() {
    Path here = Paths.get("").toAbsolutePath();
    // 测试可能从 batch-common 子目录或仓库根目录运行；定位到根
    while (here != null && !Files.exists(here.resolve("batch-config-defaults"))) {
      here = here.getParent();
    }
    return here;
  }

  @Test
  void baselineYamlIsLocatedInDedicatedModule() {
    Path baseline =
        repoRoot().resolve("batch-config-defaults/src/main/resources/batch-defaults.yml");
    assertThat(baseline).as("batch-defaults.yml 应位于 batch-config-defaults 模块").exists();
    // 顺便守护：旧位置不能再有同名文件，防止双源
    Path oldLocation = repoRoot().resolve("batch-common/src/main/resources/batch-defaults.yml");
    assertThat(oldLocation).as("batch-common/resources/batch-defaults.yml 已搬走，不可恢复").doesNotExist();
  }

  @Test
  void serviceModulesDoNotRedefineBaselineOwnedKeys() throws IOException {
    Path root = repoRoot();
    Map<String, String> drift = new LinkedHashMap<>();
    for (String module :
        List.of(
            "batch-trigger",
            "batch-orchestrator",
            "batch-worker-core",
            "batch-worker-import",
            "batch-worker-export",
            "batch-worker-process",
            "batch-worker-dispatch",
            "batch-console-api")) {
      // 同时扫 application.yml + 所有 application-<profile>.yml；profile yml 优先级更高，
      // 任何在那里复刻基线键的行为都会在运行时覆盖 batch-defaults.yml。
      Path resourcesDir = root.resolve(module).resolve("src/main/resources");
      if (!Files.exists(resourcesDir)) {
        continue;
      }
      for (Path yml : listApplicationYmls(resourcesDir)) {
        Map<String, Object> flat = flatten(loadYaml(yml));
        for (String owned : OWNED_KEYS) {
          if (flat.containsKey(owned)) {
            drift.put(
                module + "::" + yml.getFileName() + "::" + owned, String.valueOf(flat.get(owned)));
          }
        }
      }
    }
    assertThat(drift)
        .as(
            "服务模块不应复刻基线 OWNED_KEYS（详见 ADR-029；包含 application-<profile>.yml）；"
                + "如确需 overlay 请把 key 从 OWNED_KEYS 移除并在 ADR-029 记录原因")
        .isEmpty();
  }

  /**
   * 守护范围：application.yml + 所有 application-<profile>.yml，但 <b>豁免</b> application-local.yml —— 它是开发者
   * IDE 沙箱（指向本机 docker 端口），写死 localhost:15432 / 19092 等是设计意图，不属于"基线 drift"。生产/CI 不会激活 local
   * profile。
   */
  private static List<Path> listApplicationYmls(Path resourcesDir) throws IOException {
    List<Path> all = new ArrayList<>();
    try (Stream<Path> entries = Files.list(resourcesDir)) {
      entries
          .filter(Files::isRegularFile)
          .filter(
              p -> {
                String name = p.getFileName().toString();
                if (name.equals("application-local.yml")) {
                  return false;
                }
                return name.equals("application.yml")
                    || (name.startsWith("application-") && name.endsWith(".yml"));
              })
          .forEach(all::add);
    }
    return all;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadYaml(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      Object loaded = new Yaml().load(in);
      return loaded instanceof Map ? (Map<String, Object>) loaded : Map.of();
    }
  }

  private static Map<String, Object> flatten(Map<String, Object> source) {
    Map<String, Object> out = new LinkedHashMap<>();
    flattenInto(source, "", out);
    return out;
  }

  @SuppressWarnings("unchecked")
  private static void flattenInto(
      Map<String, Object> source, String prefix, Map<String, Object> out) {
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
      Object value = entry.getValue();
      if (value instanceof Map<?, ?> nested) {
        flattenInto((Map<String, Object>) nested, key, out);
      } else {
        out.put(key, value);
      }
    }
  }
}
