package com.example.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class WorkerRegistryCacheTest {

  private OrchestratorRedisSupport redis;
  private WorkerRegistryCache cache;
  private WorkerSelectorCacheProperties props;

  @BeforeEach
  void setUp() {
    redis = mock(OrchestratorRedisSupport.class);
    props = new WorkerSelectorCacheProperties();
    cache = new WorkerRegistryCache(redis, new ObjectMapper(), props);
  }

  @Test
  void disabledShouldBypassRedisAndCallLoader() {
    props.setEnabled(false);
    AtomicInteger calls = new AtomicInteger();
    List<WorkerRegistryEntity> result =
        cache.getOrLoad(
            "t1",
            "EXPORT",
            () -> {
              calls.incrementAndGet();
              return List.of();
            });
    assertThat(result).isEmpty();
    assertThat(calls.get()).isEqualTo(1);
    verify(redis, never()).getStringCache(anyString());
  }

  @Test
  void cacheMissShouldLoadAndStore() {
    props.setEnabled(true);
    when(redis.getStringCache(anyString())).thenReturn(null);
    AtomicInteger calls = new AtomicInteger();
    List<WorkerRegistryEntity> records = List.of(record(1L, "w-1"));

    List<WorkerRegistryEntity> result =
        cache.getOrLoad(
            "t1",
            "EXPORT",
            () -> {
              calls.incrementAndGet();
              return records;
            });

    assertThat(calls.get()).isEqualTo(1);
    assertThat(result)
        .hasSize(1)
        .first()
        .extracting(WorkerRegistryEntity::workerCode)
        .isEqualTo("w-1");
    verify(redis)
        .setStringCache(anyString(), anyString(), eq(Duration.ofMillis(props.getTtlMillis())));
  }

  @Test
  void cacheHitShouldNotCallLoader() throws Exception {
    props.setEnabled(true);
    String json =
        new ObjectMapper()
            .writeValueAsString(
                List.of(
                    new WorkerRegistryCache.Entry(
                        7L,
                        "t1",
                        "w-cached",
                        "EXPORT",
                        null,
                        "report",
                        "ONLINE",
                        BatchDateTimeSupport.utcNow().toEpochMilli(),
                        2,
                        10,
                        null,
                        null)));
    when(redis.getStringCache(anyString())).thenReturn(json);
    AtomicInteger calls = new AtomicInteger();

    List<WorkerRegistryEntity> result =
        cache.getOrLoad(
            "t1",
            "EXPORT",
            () -> {
              calls.incrementAndGet();
              return List.of();
            });

    assertThat(calls.get()).isZero();
    assertThat(result).hasSize(1);
    assertThat(result.get(0).workerCode()).isEqualTo("w-cached");
    assertThat(result.get(0).heartbeatAt()).isNotNull();
  }

  @Test
  void redisFailureOnReadShouldFallThroughToLoader() {
    props.setEnabled(true);
    when(redis.getStringCache(anyString())).thenThrow(new QueryTimeoutException("redis down"));
    AtomicInteger calls = new AtomicInteger();
    List<WorkerRegistryEntity> records = List.of(record(2L, "w-2"));

    List<WorkerRegistryEntity> result =
        cache.getOrLoad(
            "t1",
            "EXPORT",
            () -> {
              calls.incrementAndGet();
              return records;
            });

    assertThat(calls.get()).isEqualTo(1);
    assertThat(result).hasSize(1);
  }

  @Test
  void redisFailureOnWriteShouldStillReturnFreshResults() {
    props.setEnabled(true);
    when(redis.getStringCache(anyString())).thenReturn(null);
    doThrow(new QueryTimeoutException("redis down"))
        .when(redis)
        .setStringCache(anyString(), anyString(), any(Duration.class));
    AtomicInteger calls = new AtomicInteger();
    List<WorkerRegistryEntity> records = List.of(record(3L, "w-3"));

    List<WorkerRegistryEntity> result =
        cache.getOrLoad(
            "t1",
            "EXPORT",
            () -> {
              calls.incrementAndGet();
              return records;
            });

    assertThat(calls.get()).isEqualTo(1);
    assertThat(result).hasSize(1);
    verify(redis, times(1)).setStringCache(anyString(), anyString(), any(Duration.class));
  }

  @Test
  void corruptCacheJsonShouldFallThroughToLoader() {
    props.setEnabled(true);
    when(redis.getStringCache(anyString())).thenReturn("not-a-json");
    AtomicInteger calls = new AtomicInteger();
    List<WorkerRegistryEntity> records = List.of(record(4L, "w-4"));

    List<WorkerRegistryEntity> result =
        cache.getOrLoad(
            "t1",
            "EXPORT",
            () -> {
              calls.incrementAndGet();
              return records;
            });

    assertThat(calls.get()).isEqualTo(1);
    assertThat(result).hasSize(1);
  }

  @Test
  void cacheMissWithEmptyLoaderShouldDeleteKeyWithoutSet() {
    props.setEnabled(true);
    when(redis.getStringCache(anyString())).thenReturn(null);
    AtomicInteger calls = new AtomicInteger();
    List<WorkerRegistryEntity> result =
        cache.getOrLoad(
            "t1",
            "EXPORT",
            () -> {
              calls.incrementAndGet();
              return List.of();
            });
    assertThat(calls.get()).isEqualTo(1);
    assertThat(result).isEmpty();
    verify(redis, never()).setStringCache(anyString(), anyString(), any(Duration.class));
    verify(redis).evictCache(anyString());
  }

  @Test
  void cachedEmptyJsonArrayShouldIgnoreAndReload() throws Exception {
    props.setEnabled(true);
    when(redis.getStringCache(anyString())).thenReturn("[]");
    AtomicInteger calls = new AtomicInteger();
    List<WorkerRegistryEntity> records = List.of(record(5L, "w-5"));
    List<WorkerRegistryEntity> result =
        cache.getOrLoad(
            "t1",
            "EXPORT",
            () -> {
              calls.incrementAndGet();
              return records;
            });
    assertThat(calls.get()).isEqualTo(1);
    assertThat(result).hasSize(1);
    verify(redis)
        .setStringCache(anyString(), anyString(), eq(Duration.ofMillis(props.getTtlMillis())));
  }

  private static WorkerRegistryEntity record(Long id, String code) {
    return new WorkerRegistryEntity(
        id,
        "t1",
        code,
        "EXPORT",
        null,
        "report",
        "ONLINE",
        BatchDateTimeSupport.utcNow(),
        0,
        10,
        null,
        null);
  }
}
