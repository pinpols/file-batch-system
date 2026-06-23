package io.github.pinpols.batch.orchestrator.infrastructure.progress;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.infrastructure.progress.PipelineStageProgressCache.Snapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PipelineStageProgressCacheTest {

  private PipelineStageProgressCache cache;

  @BeforeEach
  void setUp() {
    cache = new PipelineStageProgressCache();
  }

  @Test
  void shouldStoreAndReturnSnapshot_whenPublishedWithBothFields() {
    cache.publish("ta", "w1", 123L, 1_000L);

    Map<String, Snapshot> result = cache.snapshot("ta", List.of("w1"));

    assertThat(result).hasSize(1).containsKey("w1");
    assertThat(result.get("w1").rowsProcessed()).isEqualTo(123L);
    assertThat(result.get("w1").totalRowsHint()).isEqualTo(1_000L);
    assertThat(result.get("w1").heartbeatAt()).isNotNull();
  }

  @Test
  void shouldStoreSnapshot_whenTotalRowsHintIsNull() {
    cache.publish("ta", "w1", 42L, null);

    Map<String, Snapshot> result = cache.snapshot("ta", List.of("w1"));

    assertThat(result).hasSize(1);
    assertThat(result.get("w1").rowsProcessed()).isEqualTo(42L);
    assertThat(result.get("w1").totalRowsHint()).isNull();
  }

  @Test
  void shouldRemoveEntry_whenBothFieldsNull() {
    cache.publish("ta", "w1", 100L, null);
    assertThat(cache.snapshot("ta", List.of("w1"))).hasSize(1);

    cache.publish("ta", "w1", null, null);

    assertThat(cache.snapshot("ta", List.of("w1"))).isEmpty();
  }

  @Test
  void shouldNotMixTenants() {
    cache.publish("ta", "w1", 1L, null);
    cache.publish("tb", "w1", 2L, null);

    assertThat(cache.snapshot("ta", List.of("w1")).get("w1").rowsProcessed()).isEqualTo(1L);
    assertThat(cache.snapshot("tb", List.of("w1")).get("w1").rowsProcessed()).isEqualTo(2L);
  }

  @Test
  void shouldReturnEmpty_whenWorkerCodesEmpty() {
    cache.publish("ta", "w1", 1L, null);
    assertThat(cache.snapshot("ta", List.of())).isEmpty();
  }

  @Test
  void shouldReturnEmpty_whenNoMatchingWorker() {
    cache.publish("ta", "w1", 1L, null);
    assertThat(cache.snapshot("ta", List.of("w-unknown"))).isEmpty();
  }

  @Test
  void shouldFilterOutOnlyMatchingWorkers_whenMixedRequest() {
    cache.publish("ta", "w1", 10L, null);
    cache.publish("ta", "w2", 20L, null);

    Map<String, Snapshot> result = cache.snapshot("ta", List.of("w1", "w-unknown", "w2"));

    assertThat(result).hasSize(2).containsOnlyKeys("w1", "w2");
  }
}
