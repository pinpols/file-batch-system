package com.example.batch.worker.atomic.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.worker.core.config.WorkerConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AtomicWorkerConfigurationTest {

  @Test
  void implementsWorkerConfigurationAndExposesIdentity() {
    AtomicWorkerConfiguration cfg =
        new AtomicWorkerConfiguration(
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
  void normalizesNullCapabilityTagsToEmpty() {
    AtomicWorkerConfiguration cfg =
        new AtomicWorkerConfiguration("c", "ATOMIC", "t", 15000L, "topic", "grp", null);
    assertThat(cfg.capabilityTags()).isNotNull().isEmpty();
  }
}
