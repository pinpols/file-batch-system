package io.github.pinpols.batch.orchestrator.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("批量日追平指标的聚合与失败采样")
class BatchDayCatchupMetricsTest {

  @DisplayName("聚合最大追平延迟与落后日历数且不带租户标签")
  @Test
  void shouldExposeGlobalMaximumLagAndNumberOfCalendarsBehindWithoutTenantLabels() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    BatchDayCatchupMetrics metrics = new BatchDayCatchupMetrics(registry);

    metrics.beginScan();
    metrics.recordCalendarLag(3);
    metrics.recordCalendarLag(0);
    metrics.recordCalendarLag(8);
    metrics.completeScan();

    assertThat(
            registry.get("batch.batch_day.open.catchup.remaining_days").gauge().value())
        .isEqualTo(8.0);
    assertThat(registry
            .get("batch.batch_day.open.catchup.calendars_behind")
            .gauge()
            .value())
        .isEqualTo(2.0);
    assertThat(registry
            .get("batch.batch_day.open.catchup.remaining_days")
            .gauge()
            .getId()
            .getTags())
        .isEmpty();

    metrics.beginScan();
    metrics.recordCalendarLag(0);
    metrics.completeScan();
    assertThat(
            registry.get("batch.batch_day.open.catchup.remaining_days").gauge().value())
        .isZero();
    assertThat(registry
            .get("batch.batch_day.open.catchup.calendars_behind")
            .gauge()
            .value())
        .isZero();
  }

  @DisplayName("扫描失败时保留上次完整指标快照")
  @Test
  void shouldKeepLastCompleteSnapshotWhenScanFails() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    BatchDayCatchupMetrics metrics = new BatchDayCatchupMetrics(registry);
    metrics.beginScan();
    metrics.recordCalendarLag(4);
    metrics.completeScan();

    metrics.beginScan();
    metrics.recordCalendarLag(0);
    metrics.failScan();

    assertThat(
            registry.get("batch.batch_day.open.catchup.remaining_days").gauge().value())
        .isEqualTo(4.0);
    assertThat(
            registry.get("batch.batch_day.open.catchup.scan.failures").counter().count())
        .isEqualTo(1.0);
  }
}
