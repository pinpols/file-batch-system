package io.github.pinpols.batch.worker.processes.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessWorkerConfigurationTest {

  @Test
  void capabilityTagsDefaultsToEmptyList() {
    ProcessWorkerConfiguration configuration =
        new ProcessWorkerConfiguration(
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
  void capabilityTagsReturnsConfiguredValues() {
    ProcessWorkerConfiguration configuration =
        new ProcessWorkerConfiguration(
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
