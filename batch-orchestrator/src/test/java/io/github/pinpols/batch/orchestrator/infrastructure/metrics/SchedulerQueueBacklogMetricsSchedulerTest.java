package io.github.pinpols.batch.orchestrator.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.entity.QueuePartitionBacklogStats;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class SchedulerQueueBacklogMetricsSchedulerTest {

  @Mock private JobPartitionMapper jobPartitionMapper;
  @Mock private OrchestratorGracefulShutdown gracefulShutdown;

  private SimpleMeterRegistry meterRegistry;
  private SchedulerQueueBacklogMetricsScheduler scheduler;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    meterRegistry = new SimpleMeterRegistry();
    scheduler =
        new SchedulerQueueBacklogMetricsScheduler(
            jobPartitionMapper, meterRegistry, gracefulShutdown);
    scheduler.initializeMeters();
  }

  @Test
  @DisplayName("sample: 聚合 job_partition 积压后刷新全局 gauge")
  void sampleUpdatesBacklogGauges() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(jobPartitionMapper.summarizeGlobalQueueBacklog(
            "CREATED", "WAITING", "READY", "RUNNING", "RETRYING"))
        .thenReturn(new QueuePartitionBacklogStats("ALL", 1, 2, 3, 4, 5, 120));

    scheduler.sample();

    assertGauge("batch.orchestrator.scheduler.queue.created.partitions", 1);
    assertGauge("batch.orchestrator.scheduler.queue.waiting.partitions", 2);
    assertGauge("batch.orchestrator.scheduler.queue.ready.partitions", 3);
    assertGauge("batch.orchestrator.scheduler.queue.running.partitions", 4);
    assertGauge("batch.orchestrator.scheduler.queue.retrying.partitions", 5);
    assertGauge("batch.orchestrator.scheduler.queue.queued.partitions", 11);
    assertGauge("batch.orchestrator.scheduler.queue.oldest_wait.seconds", 120);
  }

  private void assertGauge(String name, double expected) {
    assertThat(meterRegistry.get(name).gauge().value()).isEqualTo(expected);
  }
}
