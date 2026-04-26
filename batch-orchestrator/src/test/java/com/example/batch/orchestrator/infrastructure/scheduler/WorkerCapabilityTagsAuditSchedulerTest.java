package com.example.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.InvalidCapabilityTagsRecord;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkerCapabilityTagsAuditSchedulerTest {

  private static final String METRIC = "batch.worker.capability_tags.invalid.count";

  @Mock private WorkerRegistryMapper workerRegistryMapper;
  @Mock private OrchestratorGracefulShutdown gracefulShutdown;

  private SimpleMeterRegistry meterRegistry;
  private WorkerCapabilityTagsAuditScheduler scheduler;

  @BeforeEach
  void setUp() throws Exception {
    meterRegistry = new SimpleMeterRegistry();
    scheduler =
        new WorkerCapabilityTagsAuditScheduler(
            workerRegistryMapper, gracefulShutdown, meterRegistry);
    setLogSampleLimit(scheduler, 10);
    scheduler.initializeMeters();
  }

  @Test
  void drainingModeSkips() {
    when(gracefulShutdown.isDraining()).thenReturn(true);
    scheduler.auditCapabilityTags();
    verify(workerRegistryMapper, never()).selectInvalidCapabilityTags();
  }

  @Test
  void emptyResultResetsGaugeToZero() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.selectInvalidCapabilityTags()).thenReturn(List.of());
    scheduler.auditCapabilityTags();
    assertThat(readGauge()).isZero();
  }

  @Test
  void objectFormIsFlaggedInvalid() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.selectInvalidCapabilityTags())
        .thenReturn(
            List.of(new InvalidCapabilityTagsRecord("ta", "worker-1", "{\"ingest\":true}")));
    scheduler.auditCapabilityTags();
    assertThat(readGauge()).isEqualTo(1d);
  }

  @Test
  void arrayWithNonStringElementIsFlagged() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.selectInvalidCapabilityTags())
        .thenReturn(List.of(new InvalidCapabilityTagsRecord("tb", "worker-2", "[\"ingest\", 42]")));
    scheduler.auditCapabilityTags();
    assertThat(readGauge()).isEqualTo(1d);
  }

  @Test
  void validStringArrayConfirmsAppFilterClearsNoise() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.selectInvalidCapabilityTags())
        .thenReturn(
            List.of(
                new InvalidCapabilityTagsRecord("ta", "worker-x", "[\"ingest\",\"delivery\"]")));
    scheduler.auditCapabilityTags();
    assertThat(readGauge()).isZero();
  }

  @Test
  void mixedBatchCountsOnlyConfirmedInvalid() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.selectInvalidCapabilityTags())
        .thenReturn(
            List.of(
                new InvalidCapabilityTagsRecord("ta", "w-ok", "[\"ingest\"]"),
                new InvalidCapabilityTagsRecord("ta", "w-obj", "{\"a\":1}"),
                new InvalidCapabilityTagsRecord("tb", "w-scalar", "\"ingest\""),
                new InvalidCapabilityTagsRecord("tb", "w-num-elem", "[\"a\", 1]")));
    scheduler.auditCapabilityTags();
    assertThat(readGauge()).isEqualTo(3d);
  }

  @Test
  void nullOrBlankRawValueIsNotCountedInvalid() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.selectInvalidCapabilityTags())
        .thenReturn(
            List.of(
                new InvalidCapabilityTagsRecord("ta", "w-null", null),
                new InvalidCapabilityTagsRecord("ta", "w-blank", "  ")));
    scheduler.auditCapabilityTags();
    assertThat(readGauge()).isZero();
  }

  private double readGauge() {
    Gauge g = meterRegistry.find(METRIC).gauge();
    assertThat(g).isNotNull();
    return g.value();
  }

  private static void setLogSampleLimit(WorkerCapabilityTagsAuditScheduler target, int value)
      throws Exception {
    Field f = WorkerCapabilityTagsAuditScheduler.class.getDeclaredField("logSampleLimit");
    f.setAccessible(true);
    f.setInt(target, value);
  }
}
