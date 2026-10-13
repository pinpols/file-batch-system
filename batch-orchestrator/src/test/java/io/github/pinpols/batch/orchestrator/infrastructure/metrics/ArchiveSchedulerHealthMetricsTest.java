package io.github.pinpols.batch.orchestrator.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("归档调度健康指标 - 注册各调度并记录最近正常返回时间")
class ArchiveSchedulerHealthMetricsTest {

  private static final String METRIC_NAME =
      "batch.archive.scheduler.last.success.timestamp.seconds";

  private SimpleMeterRegistry meterRegistry;
  private ArchiveSchedulerHealthMetrics metrics;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    metrics = new ArchiveSchedulerHealthMetrics(meterRegistry);
  }

  @Test
  @DisplayName("注册后初始值为零且重复注册不创建重复指标")
  void shouldRegisterZeroValuedGaugeIdempotently() {
    metrics.register("outbox");
    metrics.register("outbox");

    Gauge gauge = meterRegistry.get(METRIC_NAME).tag("scheduler", "outbox").gauge();
    assertThat(gauge.value()).isZero();
    assertThat(meterRegistry.find(METRIC_NAME).gauges()).hasSize(1);
  }

  @Test
  @DisplayName("正常返回后更新对应调度的 Unix 时间戳")
  void shouldRecordLastSuccessTimestamp() {
    long before = System.currentTimeMillis() / 1000;

    metrics.markSuccess("workflow");

    double value =
        meterRegistry.get(METRIC_NAME).tag("scheduler", "workflow").gauge().value();
    long after = System.currentTimeMillis() / 1000;
    assertThat(value).isBetween((double) before, (double) after);
  }
}
