package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

@DisplayName("工作节点注册表缓存,验证开关,命中,未命中,读写故障与脏数据场景下的加载与回填行为")
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
  @DisplayName("缓存开关关闭时不读取缓存,直接调用加载器并返回其结果")
  void shouldBypassCacheAndCallLoader_whenCacheDisabled() {
    props.setEnabled(false);
    AtomicInteger calls = new AtomicInteger();
    List<WorkerRegistryEntity> result = cache.getOrLoad("t1", "EXPORT", () -> {
      calls.incrementAndGet();
      return List.of();
    });
    assertThat(result).isEmpty();
    assertThat(calls.get()).isEqualTo(1);
    verify(redis, never()).getStringCache(anyString());
  }

  @Test
  @DisplayName("缓存未命中时调用加载器一次,并按配置的过期时长回填缓存")
  void shouldLoadAndStore_whenCacheMiss() {
    props.setEnabled(true);
    when(redis.getStringCache(anyString())).thenReturn(null);
    AtomicInteger calls = new AtomicInteger();
    List<WorkerRegistryEntity> records = List.of(workerRecord(1L, "w-1"));

    List<WorkerRegistryEntity> result = cache.getOrLoad("t1", "EXPORT", () -> {
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
  @DisplayName("缓存命中时跳过加载器,并按缓存内容还原节点编码,路由编码与心跳时间")
  void shouldReturnCachedResult_whenCacheHit() throws Exception {
    props.setEnabled(true);
    String json = new ObjectMapper()
        .writeValueAsString(List.of(new WorkerRegistryCache.Entry(
            7L,
            "t1",
            "w-cached",
            "export-heavy",
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

    List<WorkerRegistryEntity> result = cache.getOrLoad("t1", "EXPORT", () -> {
      calls.incrementAndGet();
      return List.of();
    });

    assertThat(calls.get()).isZero();
    assertThat(result).hasSize(1);
    assertThat(result.get(0).workerCode()).isEqualTo("w-cached");
    assertThat(result.get(0).routingCode()).isEqualTo("export-heavy");
    assertThat(result.get(0).heartbeatAt()).isNotNull();
  }

  @Test
  @DisplayName("缓存读取抛出超时时降级调用加载器,并返回本次加载结果")
  void shouldFallThroughToLoader_whenReadFails() {
    props.setEnabled(true);
    when(redis.getStringCache(anyString())).thenThrow(new QueryTimeoutException("redis down"));
    AtomicInteger calls = new AtomicInteger();
    List<WorkerRegistryEntity> records = List.of(workerRecord(2L, "w-2"));

    List<WorkerRegistryEntity> result = cache.getOrLoad("t1", "EXPORT", () -> {
      calls.incrementAndGet();
      return records;
    });

    assertThat(calls.get()).isEqualTo(1);
    assertThat(result).hasSize(1);
  }

  @Test
  @DisplayName("缓存写入失败时仍返回本次加载的新鲜结果,不向调用方抛出异常")
  void shouldReturnFreshResults_whenWriteFails() {
    props.setEnabled(true);
    when(redis.getStringCache(anyString())).thenReturn(null);
    doThrow(new QueryTimeoutException("redis down"))
        .when(redis)
        .setStringCache(anyString(), anyString(), any(Duration.class));
    AtomicInteger calls = new AtomicInteger();
    List<WorkerRegistryEntity> records = List.of(workerRecord(3L, "w-3"));

    List<WorkerRegistryEntity> result = cache.getOrLoad("t1", "EXPORT", () -> {
      calls.incrementAndGet();
      return records;
    });

    assertThat(calls.get()).isEqualTo(1);
    assertThat(result).hasSize(1);
    verify(redis, times(1)).setStringCache(anyString(), anyString(), any(Duration.class));
  }

  @Test
  @DisplayName("缓存内容无法解析时降级调用加载器,避免脏数据导致查询失败")
  void shouldFallThroughToLoader_whenCachedPayloadCorrupt() {
    props.setEnabled(true);
    when(redis.getStringCache(anyString())).thenReturn("not-a-json");
    AtomicInteger calls = new AtomicInteger();
    List<WorkerRegistryEntity> records = List.of(workerRecord(4L, "w-4"));

    List<WorkerRegistryEntity> result = cache.getOrLoad("t1", "EXPORT", () -> {
      calls.incrementAndGet();
      return records;
    });

    assertThat(calls.get()).isEqualTo(1);
    assertThat(result).hasSize(1);
  }

  @Test
  @DisplayName("加载器返回空集合时不回填缓存,并清除该缓存键")
  void shouldDeleteKeyWithoutStoring_whenLoaderReturnsEmpty() {
    props.setEnabled(true);
    when(redis.getStringCache(anyString())).thenReturn(null);
    AtomicInteger calls = new AtomicInteger();
    List<WorkerRegistryEntity> result = cache.getOrLoad("t1", "EXPORT", () -> {
      calls.incrementAndGet();
      return List.of();
    });
    assertThat(calls.get()).isEqualTo(1);
    assertThat(result).isEmpty();
    verify(redis, never()).setStringCache(anyString(), anyString(), any(Duration.class));
    verify(redis).evictCache(anyString());
  }

  @Test
  @DisplayName("缓存中为空数组时视为未命中,重新加载并按过期时长写回缓存")
  void shouldIgnoreAndReload_whenCachedArrayEmpty() {
    props.setEnabled(true);
    when(redis.getStringCache(anyString())).thenReturn("[]");
    AtomicInteger calls = new AtomicInteger();
    List<WorkerRegistryEntity> records = List.of(workerRecord(5L, "w-5"));
    List<WorkerRegistryEntity> result = cache.getOrLoad("t1", "EXPORT", () -> {
      calls.incrementAndGet();
      return records;
    });
    assertThat(calls.get()).isEqualTo(1);
    assertThat(result).hasSize(1);
    verify(redis)
        .setStringCache(anyString(), anyString(), eq(Duration.ofMillis(props.getTtlMillis())));
  }

  private static WorkerRegistryEntity workerRecord(Long id, String code) {
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
