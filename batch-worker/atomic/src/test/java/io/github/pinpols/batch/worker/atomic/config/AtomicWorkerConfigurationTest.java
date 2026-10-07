package io.github.pinpols.batch.worker.atomic.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("原子 Worker 配置: 身份标识与能力标签构造")
class AtomicWorkerConfigurationTest {

  @Test
  @DisplayName("构造后应实现统一 Worker 配置契约, 并暴露节点编码, 类型, 主题与能力标签")
  void shouldExposeWorkerIdentityAndCapabilityTags_whenConfigured() {
    AtomicWorkerConfiguration cfg = new AtomicWorkerConfiguration(
        "atomic-node-1",
        "ATOMIC",
        "default-tenant",
        15000L,
        "batch.task.dispatch.atomic",
        "batch-worker-atomic",
        List.of("shell", "sql"));
    assertThat(cfg).isInstanceOf(WorkerConfiguration.class);
    assertThat(cfg.workerCode()).isEqualTo("atomic-node-1");
    assertThat(cfg.workerType()).isEqualTo("ATOMIC");
    assertThat(cfg.topic()).isEqualTo("batch.task.dispatch.atomic");
    assertThat(cfg.capabilityTags()).containsExactly("shell", "sql");
  }

  @Test
  @DisplayName("能力标签传入空值时, 应归一化为空集合而不是保留空引用")
  void shouldNormalizeNullCapabilityTagsToEmpty_whenTagsNull() {
    AtomicWorkerConfiguration cfg =
        new AtomicWorkerConfiguration("c", "ATOMIC", "t", 15000L, "topic", "grp", null);
    assertThat(cfg.capabilityTags()).isNotNull().isEmpty();
  }
}
