package com.example.batch.worker.spi.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.worker.core.config.WorkerConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpiWorkerConfigurationTest {

  @Test
  void implementsWorkerConfigurationAndExposesIdentity() {
    SpiWorkerConfiguration cfg =
        new SpiWorkerConfiguration(
            "spi-node-1",
            "TASK",
            "default-tenant",
            15000L,
            "batch.task.dispatch.task",
            "batch-worker-spi",
            List.of("shell", "sql"));
    assertThat(cfg).isInstanceOf(WorkerConfiguration.class);
    assertThat(cfg.workerCode()).isEqualTo("spi-node-1");
    assertThat(cfg.workerType()).isEqualTo("TASK");
    assertThat(cfg.topic()).isEqualTo("batch.task.dispatch.task");
    assertThat(cfg.capabilityTags()).containsExactly("shell", "sql");
  }

  @Test
  void normalizesNullCapabilityTagsToEmpty() {
    SpiWorkerConfiguration cfg =
        new SpiWorkerConfiguration("c", "TASK", "t", 15000L, "topic", "grp", null);
    assertThat(cfg.capabilityTags()).isNotNull().isEmpty();
  }
}
