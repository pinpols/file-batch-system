package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * P2-3: Redis 缓存 ONLINE worker 列表，避免每秒派发都打 PG。
 *
 * <p>Key 形态：{@code worker:reg:{tenantId}:{workerGroup|_}}；value 是 slim {@link Entry} 列表的 JSON。 默认
 * 5s TTL（{@code batch.scheduler.worker-cache.ttl-millis}），命中即返回，未命中走 loader 回退 并把结果写入 Redis。{@code
 * enabled=false} 时直通 loader，不调 Redis；默认启用，Redis 故障时按下述策略回退数据库。
 *
 * <p><b>失效策略</b>：仅靠 TTL（5s 内的轻微 staleness 可接受——派发本就有重试）；不做 pub/sub 主动失效，避免心跳写路径耦合 Redis。worker
 * 状态突变（drain / offline）最多 5s 才被 selector 看到。<b>空候选列表不写缓存</b>，避免「先查后插 worker」场景长期命中空快照。
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

  public List<WorkerRegistryEntity> getOrLoad(
      String tenantId, String workerGroup, Supplier<List<WorkerRegistryEntity>> loader) {
    if (!properties.isEnabled()) {
      return loader.get();
    }
    String key = key(tenantId, workerGroup);
    try {
      String cached = redis.getStringCache(key);
      if (cached != null && !cached.isBlank()) {
        List<Entry> entries = objectMapper.readValue(cached, new TypeReference<List<Entry>>() {});
        // 绝不命中「空列表」快照：否则 PG 刚插入 ONLINE worker 仍会在 TTL 内被判无候选（派发永久 WAITING）。
        if (!entries.isEmpty()) {
          return toRecords(entries);
        }
      }
    } catch (Exception ex) {
      String causeSummary = SwallowedExceptionLogger.summary(ex);
      log.warn(
          "worker cache read failed; falling back to DB: tenant={}, group={}, cause={}",
          tenantId,
          workerGroup,
          causeSummary);
    }
    List<WorkerRegistryEntity> fresh = loader.get();
    try {
      if (!fresh.isEmpty()) {
        List<Entry> entries = toEntries(fresh);
        String json = objectMapper.writeValueAsString(entries);
        redis.setStringCache(key, json, Duration.ofMillis(properties.getTtlMillis()));
      } else {
        redis.evictCache(key);
      }
    } catch (Exception ex) {
      String causeSummary = SwallowedExceptionLogger.summary(ex);
      log.debug(
          "worker cache write/delete failed: tenant={}, group={}: {}",
          tenantId,
          workerGroup,
          causeSummary);
    }
    return fresh;
  }

  /**
   * 驱逐单个 selector 缓存键（运维 / IT：插入或刷新 worker_registry 后避免命中陈旧 ONLINE 列表）。
   *
   * <p>{@code enabled=false} 时为 no-op。
   */
  public void evict(String tenantId, String workerGroup) {
    if (!properties.isEnabled()) {
      return;
    }
    try {
      redis.evictCache(key(tenantId, workerGroup));
    } catch (Exception ex) {
      String causeSummary = SwallowedExceptionLogger.summary(ex);
      log.debug(
          "worker cache evict failed: tenant={}, group={}, cause={}",
          tenantId,
          workerGroup,
          causeSummary);
    }
  }

  private static String key(String tenantId, String workerGroup) {
    String safeTenantId = safe(tenantId);
    String safeWorkerGroup = safe(workerGroup);
    return "worker:reg:%s:%s".formatted(safeTenantId, safeWorkerGroup);
  }

  private static String safe(String value) {
    return value == null || value.isBlank() ? "_" : value.replace(':', '_');
  }

  private static List<Entry> toEntries(List<WorkerRegistryEntity> records) {
    List<Entry> entries = new ArrayList<>(records.size());
    for (WorkerRegistryEntity r : records) {
      Entry entry = Entry.builder()
          .id(r.id())
          .tenantId(r.tenantId())
          .workerCode(r.workerCode())
          .workerPoolCode(r.workerPoolCode())
          .workerGroup(r.workerGroup())
          .capabilityTagsJson(
              r.capabilityTags() == null ? null : r.capabilityTags().getValue())
          .resourceTag(r.resourceTag())
          .status(r.status())
          .heartbeatMillis(r.heartbeatAt() == null ? null : r.heartbeatAt().toEpochMilli())
          .currentLoad(r.currentLoad())
          .maxConcurrent(r.maxConcurrent())
          .drainStartedMillis(
              r.drainStartedAt() == null ? null : r.drainStartedAt().toEpochMilli())
          .drainDeadlineMillis(
              r.drainDeadlineAt() == null ? null : r.drainDeadlineAt().toEpochMilli())
          .build();
      entries.add(entry);
    }
    return entries;
  }

  private static List<WorkerRegistryEntity> toRecords(List<Entry> entries) {
    List<WorkerRegistryEntity> records = new ArrayList<>(entries.size());
    for (Entry e : entries) {
      // 缓存快照不包含运行指纹及任务能力；未缓存字段保持 null，展示仍由数据库直读。
      WorkerRegistryEntity record = WorkerRegistryEntity.builder()
          .id(e.id)
          .tenantId(e.tenantId)
          .workerCode(e.workerCode)
          .workerGroup(e.workerGroup)
          .capabilityTags(
              EmptyChecks.isNull(e.capabilityTagsJson)
                  ? null
                  : new JsonbString(e.capabilityTagsJson))
          .resourceTag(e.resourceTag)
          .status(e.status)
          .heartbeatAt(e.heartbeatMillis == null ? null : Instant.ofEpochMilli(e.heartbeatMillis))
          .currentLoad(e.currentLoad)
          .maxConcurrent(e.maxConcurrent)
          .drainStartedAt(
              e.drainStartedMillis == null ? null : Instant.ofEpochMilli(e.drainStartedMillis))
          .drainDeadlineAt(
              EmptyChecks.isNull(e.drainDeadlineMillis)
                  ? null
                  : Instant.ofEpochMilli(e.drainDeadlineMillis))
          .workerPoolCode(e.workerPoolCode)
          .build();
      records.add(record);
    }
    return records;
  }

  /** Slim DTO for cache serialization；故意用 fields，避免 JsonbString 反序列化问题。 */
  @Builder
  public record Entry(
      Long id,
      String tenantId,
      String workerCode,
      String workerPoolCode,
      String workerGroup,
      String capabilityTagsJson,
      String resourceTag,
      String status,
      Long heartbeatMillis,
      Integer currentLoad,
      Integer maxConcurrent,
      Long drainStartedMillis,
      Long drainDeadlineMillis) {}
}
