package io.github.pinpols.batch.orchestrator.infrastructure.progress;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.WorkerPipelineProgressDto;
import io.github.pinpols.batch.orchestrator.infrastructure.progress.PipelineStageProgressCache.PipelineSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PipelineStageProgressCacheTest {

  private PipelineStageProgressCache cache;
  private MutableClock clock;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
    cache = new PipelineStageProgressCache(clock);
  }

  @Test
  void shouldNotMixTenants() {
    cache.publish("ta", "w1", List.of(new WorkerPipelineProgressDto(1L, 99L, "LOAD", 1L, null)));
    cache.publish("tb", "w1", List.of(new WorkerPipelineProgressDto(1L, 99L, "LOAD", 2L, null)));
    assertThat(cache.snapshotByPipeline("ta", 99L))
        .extracting(PipelineSnapshot::rowsProcessed)
        .containsExactly(1L);
    assertThat(cache.snapshotByPipeline("tb", 99L))
        .extracting(PipelineSnapshot::rowsProcessed)
        .containsExactly(2L);
    assertThat(cache.snapshotByPipeline("tc", 99L)).isEmpty();
  }

  @Test
  void missingOrInvalidSnapshotDoesNotKeepOldProgress() {
    cache.publish("ta", "w1", List.of(new WorkerPipelineProgressDto(1L, 99L, "LOAD", 1L, null)));
    cache.publish("ta", "w1", null);
    assertThat(cache.snapshotByPipeline("ta", 99L)).isEmpty();
    cache.publish("ta", "w1", List.of(new WorkerPipelineProgressDto(null, 99L, "LOAD", 1L, null)));
    assertThat(cache.snapshotByPipeline("ta", 99L)).isEmpty();
    assertThat(cache.snapshotByPipeline("ta", null)).isEmpty();
  }

  @Test
  void shouldAggregateConcurrentTasksByPipelineAndStage() {
    cache.publish(
        "ta",
        "worker-node-1",
        List.of(
            new WorkerPipelineProgressDto(11L, 99L, "LOAD", 40L, 100L),
            new WorkerPipelineProgressDto(12L, 99L, "LOAD", 30L, 100L),
            new WorkerPipelineProgressDto(13L, 99L, "VALIDATE", 10L, null)));

    Map<String, PipelineSnapshot> result = cache.snapshotByPipeline("ta", 99L).stream()
        .collect(Collectors.toMap(PipelineSnapshot::stageCode, item -> item));

    assertThat(result.get("LOAD").rowsProcessed()).isEqualTo(70L);
    assertThat(result.get("LOAD").totalRowsHint()).isEqualTo(200L);
    assertThat(result.get("VALIDATE").rowsProcessed()).isEqualTo(10L);
    assertThat(result.get("VALIDATE").totalRowsHint()).isNull();
  }

  @Test
  void shouldRemoveTaskMissingFromNextWorkerHeartbeat() {
    cache.publish(
        "ta", "worker-node-1", List.of(new WorkerPipelineProgressDto(11L, 99L, "LOAD", 40L, null)));
    cache.publish("ta", "worker-node-1", List.of());

    assertThat(cache.snapshotByPipeline("ta", 99L)).isEmpty();
  }

  @Test
  void shouldNotMixPipelinesRunningOnSameWorker() {
    cache.publish(
        "ta",
        "worker-node-1",
        List.of(
            new WorkerPipelineProgressDto(11L, 99L, "LOAD", 40L, null),
            new WorkerPipelineProgressDto(12L, 100L, "LOAD", 900L, null)));

    assertThat(cache.snapshotByPipeline("ta", 99L))
        .extracting(PipelineSnapshot::rowsProcessed)
        .containsExactly(40L);
    assertThat(cache.snapshotByPipeline("ta", 100L))
        .extracting(PipelineSnapshot::rowsProcessed)
        .containsExactly(900L);
  }

  @Test
  void oldWorkerCleanupDoesNotRemoveNewWorkerSnapshot() {
    cache.publish(
        "ta", "old-worker", List.of(new WorkerPipelineProgressDto(11L, 99L, "LOAD", 1L, 100L)));
    cache.publish(
        "ta", "new-worker", List.of(new WorkerPipelineProgressDto(11L, 99L, "LOAD", 2L, 100L)));
    cache.publish("ta", "old-worker", List.of());

    assertThat(cache.snapshotByPipeline("ta", 99L))
        .extracting(PipelineSnapshot::rowsProcessed)
        .containsExactly(2L);
  }

  @Test
  void oneUnknownShardTotalPreventsPartialPercentage() {
    cache.publish("ta", "w1", List.of(new WorkerPipelineProgressDto(11L, 99L, "LOAD", 40L, 100L)));
    cache.publish("ta", "w2", List.of(new WorkerPipelineProgressDto(12L, 99L, "LOAD", 30L, null)));

    assertThat(cache.snapshotByPipeline("ta", 99L)).singleElement().satisfies(snapshot -> {
      assertThat(snapshot.rowsProcessed()).isEqualTo(70L);
      assertThat(snapshot.totalRowsHint()).isNull();
    });
  }

  @Test
  void shouldRemoveExpiredTaskFromPipeline() {
    cache.publish(
        "ta", "worker-node-1", List.of(new WorkerPipelineProgressDto(11L, 99L, "LOAD", 40L, null)));
    clock.advance(Duration.ofMinutes(6));

    assertThat(cache.snapshotByPipeline("ta", 99L)).isEmpty();
  }

  private static final class MutableClock extends Clock {

    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(current, zone);
    }

    @Override
    public Instant instant() {
      return current;
    }

    private void advance(Duration duration) {
      current = current.plus(duration);
    }
  }
}
