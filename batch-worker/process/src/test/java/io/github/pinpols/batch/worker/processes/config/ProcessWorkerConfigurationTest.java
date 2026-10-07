package io.github.pinpols.batch.worker.processes.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("处理 Worker 配置:能力标签未配置时的默认空列表,以及已配置时原样返回")
class ProcessWorkerConfigurationTest {

  @Test
  @DisplayName("未配置能力标签时返回空列表,不抛异常")
  void shouldReturnEmptyList_whenCapabilityTagsNotConfigured() {
    ProcessWorkerConfiguration configuration = new ProcessWorkerConfiguration(
        "process-node-1",
        "PROCESS",
        "tenant-a",
        15_000L,
        "batch.task.dispatch.process",
        "batch-worker-process",
        null);

    assertThat(configuration.capabilityTags()).isEmpty();
  }

  @Test
  @DisplayName("已配置能力标签时原样返回配置值")
  void shouldReturnConfiguredValues_whenCapabilityTagsPresent() {
    ProcessWorkerConfiguration configuration = new ProcessWorkerConfiguration(
        "process-node-1",
        "PROCESS",
        "tenant-a",
        15_000L,
        "batch.task.dispatch.process",
        "batch-worker-process",
        List.of("settlement"));

    assertThat(configuration.capabilityTags()).containsExactly("settlement");
  }
}
