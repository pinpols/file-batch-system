package com.example.batch.worker.processes.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ProcessMetricsTest {

  @Test
  void noopFactory_doesNotThrow_whenRegistryAbsent() {
    ProcessMetrics metrics = ProcessMetrics.noop();

    assertThatCode(
            () -> {
              metrics.recordComputeStagedRows("t1", 10);
              metrics.recordCommitPublishedRows("t1", 5);
              metrics.incrementValidationFailed("t1", "rule1");
              metrics.recordStageDuration("PREPARE", "t1", true, 12345L);
            })
        .doesNotThrowAnyException();
  }

  @Test
  void recordsCreateMetersInRegistry_whenRegistryAvailable() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ProcessMetrics metrics = new ProcessMetrics(asProvider(registry));

    metrics.recordComputeStagedRows("t1", 100);
    metrics.recordCommitPublishedRows("t1", 90);
    metrics.incrementValidationFailed("t1", "row_count_positive");
    metrics.incrementValidationFailed("t1", "row_count_positive");
    metrics.recordStageDuration("COMPUTE", "t1", true, 5_000_000L);

    assertThat(registry.find(ProcessMetrics.STAGED_ROWS).summary().count()).isEqualTo(1);
    assertThat(registry.find(ProcessMetrics.STAGED_ROWS).summary().totalAmount()).isEqualTo(100);

    assertThat(registry.find(ProcessMetrics.PUBLISHED_ROWS).summary().count()).isEqualTo(1);
    assertThat(registry.find(ProcessMetrics.PUBLISHED_ROWS).summary().totalAmount()).isEqualTo(90);

    assertThat(registry.find(ProcessMetrics.VALIDATION_FAILED).counter().count()).isEqualTo(2.0);

    assertThat(registry.find(ProcessMetrics.STAGE_DURATION).timer().count()).isEqualTo(1);
  }

  @Test
  void timerTagsByStageTenantAndSuccess_distinguishMeters() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ProcessMetrics metrics = new ProcessMetrics(asProvider(registry));

    metrics.recordStageDuration("PREPARE", "t1", true, 1_000_000L);
    metrics.recordStageDuration("PREPARE", "t1", false, 1_000_000L);
    metrics.recordStageDuration("COMPUTE", "t1", true, 1_000_000L);
    metrics.recordStageDuration("PREPARE", "t1", true, 1_000_000L);

    // 三种独立 tag 组合 → 3 个 timer 实例;同 tag 组合的两次 record 累计在一个 timer 上。
    assertThat(registry.find(ProcessMetrics.STAGE_DURATION).timers()).hasSize(3);
    assertThat(
            registry
                .find(ProcessMetrics.STAGE_DURATION)
                .tag("stage", "PREPARE")
                .tag("success", "true")
                .timer()
                .count())
        .isEqualTo(2);
  }

  @Test
  void normalizesNullOrBlankTags_toUnknown() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ProcessMetrics metrics = new ProcessMetrics(asProvider(registry));

    metrics.incrementValidationFailed(null, null);
    metrics.incrementValidationFailed("", " ");

    // 两次 null/blank tag 应当合并到同一个 (tenantId=unknown, ruleName=unknown) 计数器。
    assertThat(registry.find(ProcessMetrics.VALIDATION_FAILED).counters()).hasSize(1);
    assertThat(registry.find(ProcessMetrics.VALIDATION_FAILED).counter().count()).isEqualTo(2.0);
  }

  private static ObjectProvider<MeterRegistry> asProvider(MeterRegistry registry) {
    // 测试用极简 ObjectProvider:仅 getIfAvailable() 返回真实 registry,其它方法 ProcessMetrics 不调用。
    return new ObjectProvider<>() {
      @Override
      public MeterRegistry getObject() {
        return registry;
      }

      @Override
      public MeterRegistry getObject(Object... args) {
        return registry;
      }

      @Override
      public MeterRegistry getIfAvailable() {
        return registry;
      }

      @Override
      public MeterRegistry getIfUnique() {
        return registry;
      }
    };
  }
}
