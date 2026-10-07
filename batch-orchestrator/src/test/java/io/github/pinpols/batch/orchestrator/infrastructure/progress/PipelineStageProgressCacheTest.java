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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("流水线阶段进度缓存,验证多租户与多流水线之间的进度隔离,同阶段并行任务的聚合口径以及心跳缺失与过期任务的清理行为")
class PipelineStageProgressCacheTest {

  private PipelineStageProgressCache cache;
  private MutableClock clock;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
    cache = new PipelineStageProgressCache(clock);
  }

  @Test
  @DisplayName("不同租户上报相同流水线与阶段时进度互不串扰,未上报的租户查询为空")
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
  @DisplayName("心跳负载缺失或任务标识缺失时清空既有进度,且流水线标识缺失时查询为空")
  void shouldClearPreviousProgress_whenHeartbeatMissingOrTaskIdAbsent() {
    cache.publish("ta", "w1", List.of(new WorkerPipelineProgressDto(1L, 99L, "LOAD", 1L, null)));
    cache.publish("ta", "w1", null);
    assertThat(cache.snapshotByPipeline("ta", 99L)).isEmpty();
    cache.publish("ta", "w1", List.of(new WorkerPipelineProgressDto(null, 99L, "LOAD", 1L, null)));
    assertThat(cache.snapshotByPipeline("ta", 99L)).isEmpty();
    assertThat(cache.snapshotByPipeline("ta", null)).isEmpty();
  }

  @Test
  @DisplayName("同一流水线同一阶段由多个任务并行上报时进度累加,总量在各方都给出时才汇总")
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
  @DisplayName("执行节点下一次心跳不再上报某任务时移除该任务进度,避免陈旧数据长期残留")
  void shouldRemoveTaskMissingFromNextWorkerHeartbeat() {
    cache.publish(
        "ta", "worker-node-1", List.of(new WorkerPipelineProgressDto(11L, 99L, "LOAD", 40L, null)));
    cache.publish("ta", "worker-node-1", List.of());

    assertThat(cache.snapshotByPipeline("ta", 99L)).isEmpty();
  }

  @Test
  @DisplayName("同一执行节点上报多个流水线的进度时按流水线标识隔离,互不串扰")
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
  @DisplayName("旧执行节点上报空心跳时只清理自身任务,不影响新节点已上报的进度")
  void shouldKeepNewNodeSnapshot_whenOldNodeReportsEmptyHeartbeat() {
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
  @DisplayName("任一分片缺少总量提示时不汇总总量,避免给出不完整的进度占比")
  void shouldWithholdAggregatedTotal_whenAnyShardTotalMissing() {
    cache.publish("ta", "w1", List.of(new WorkerPipelineProgressDto(11L, 99L, "LOAD", 40L, 100L)));
    cache.publish("ta", "w2", List.of(new WorkerPipelineProgressDto(12L, 99L, "LOAD", 30L, null)));

    assertThat(cache.snapshotByPipeline("ta", 99L)).singleElement().satisfies(snapshot -> {
      assertThat(snapshot.rowsProcessed()).isEqualTo(70L);
      assertThat(snapshot.totalRowsHint()).isNull();
    });
  }

  @Test
  @DisplayName("任务超过心跳有效期后自动过期移除,查询结果为空")
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
