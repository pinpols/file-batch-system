package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.domain.value.JsonbString;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * P2-3: Redis 缓存 ONLINE worker 列表，避免每秒派发都打 PG。
 *
 * <p>Key 形态：{@code worker:reg:{tenantId}:{workerGroup|_}}；value 是 slim {@link Entry} 列表的 JSON。 默认
 * 5s TTL（{@code batch.scheduler.worker-cache.ttl-millis}），命中即返回，未命中走 loader 兜底 并把结果写入 Redis。{@code
 * enabled=false}（默认）时直通 loader，不调 Redis。
 *
 * <p><b>失效策略</b>：仅靠 TTL（5s 内的轻微 staleness 可接受——派发本就有重试）；不做 pub/sub 主动失效，避免心跳写路径耦合 Redis。worker
 * 状态突变（drain / offline）最多 5s 才被 selector 看到。
 *
 * <p><b>Fail-open</b>：Redis 异常 / 反序列化失败一律 fall through 到 loader（DB），仅记 WARN。 缓存只是优化，不能成为派发的硬依赖。
 */
@Slf4j
@Component
public class WorkerRegistryCache {

  private final OrchestratorRedisSupport redis;
  private final ObjectMapper objectMapper;
  private final WorkerSelectorCacheProperties properties;

  public WorkerRegistryCache(
      OrchestratorRedisSupport redis,
      ObjectMapper objectMapper,
      WorkerSelectorCacheProperties properties) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  public List<WorkerRegistryRecord> getOrLoad(
      String tenantId, String workerGroup, Supplier<List<WorkerRegistryRecord>> loader) {
    if (!properties.isEnabled()) {
      return loader.get();
    }
    String key = key(tenantId, workerGroup);
    try {
      String cached = redis.redisTemplate().opsForValue().get(key);
      if (cached != null && !cached.isBlank()) {
        List<Entry> entries = objectMapper.readValue(cached, new TypeReference<List<Entry>>() {});
        return toRecords(entries);
      }
    } catch (Exception ex) {
      log.warn(
          "worker cache read failed; falling back to DB: tenant={}, group={}, cause={}",
          tenantId,
          workerGroup,
          ex.getMessage());
    }
    List<WorkerRegistryRecord> fresh = loader.get();
    try {
      String json = objectMapper.writeValueAsString(toEntries(fresh));
      redis
          .redisTemplate()
          .opsForValue()
          .set(key, json, Duration.ofMillis(properties.getTtlMillis()));
    } catch (Exception ex) {
      log.debug(
          "worker cache write failed: tenant={}, group={}: {}",
          tenantId,
          workerGroup,
          ex.getMessage());
    }
    return fresh;
  }

  private static String key(String tenantId, String workerGroup) {
    return "worker:reg:%s:%s".formatted(safe(tenantId), safe(workerGroup));
  }

  private static String safe(String value) {
    return value == null || value.isBlank() ? "_" : value.replace(':', '_');
  }

  private static List<Entry> toEntries(List<WorkerRegistryRecord> records) {
    List<Entry> entries = new ArrayList<>(records.size());
    for (WorkerRegistryRecord r : records) {
      entries.add(
          new Entry(
              r.id(),
              r.tenantId(),
              r.workerCode(),
              r.workerGroup(),
              r.capabilityTags() == null ? null : r.capabilityTags().getValue(),
              r.resourceTag(),
              r.status(),
              r.heartbeatAt() == null ? null : r.heartbeatAt().toEpochMilli(),
              r.currentLoad(),
              r.drainStartedAt() == null ? null : r.drainStartedAt().toEpochMilli(),
              r.drainDeadlineAt() == null ? null : r.drainDeadlineAt().toEpochMilli()));
    }
    return entries;
  }

  private static List<WorkerRegistryRecord> toRecords(List<Entry> entries) {
    List<WorkerRegistryRecord> records = new ArrayList<>(entries.size());
    for (Entry e : entries) {
      records.add(
          new WorkerRegistryRecord(
              e.id,
              e.tenantId,
              e.workerCode,
              e.workerGroup,
              e.capabilityTagsJson == null ? null : new JsonbString(e.capabilityTagsJson),
              e.resourceTag,
              e.status,
              e.heartbeatMillis == null ? null : Instant.ofEpochMilli(e.heartbeatMillis),
              e.currentLoad,
              e.drainStartedMillis == null ? null : Instant.ofEpochMilli(e.drainStartedMillis),
              e.drainDeadlineMillis == null ? null : Instant.ofEpochMilli(e.drainDeadlineMillis)));
    }
    return records;
  }

  /** Slim DTO for cache serialization；故意用 fields，避免 JsonbString 反序列化坑。 */
  public record Entry(
      Long id,
      String tenantId,
      String workerCode,
      String workerGroup,
      String capabilityTagsJson,
      String resourceTag,
      String status,
      Long heartbeatMillis,
      Integer currentLoad,
      Long drainStartedMillis,
      Long drainDeadlineMillis) {}
}
