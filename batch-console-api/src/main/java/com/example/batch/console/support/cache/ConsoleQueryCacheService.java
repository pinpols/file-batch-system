package com.example.batch.console.support.cache;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import java.time.Duration;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 控制台高频查询的 Redis 缓存层。
 *
 * <p>缓存策略：
 *
 * <ul>
 *   <li>Meta 枚举 / 配置选项 — TTL 5 分钟，配置变更时手动 evict
 *   <li>Dashboard 汇总 — TTL 10 秒，避免多用户同时刷导致 DB 重复聚合
 *   <li>调度快照 — TTL 30 秒，分钟级数据无需实时
 * </ul>
 *
 * <p>Redis 不可用时降级到直接查 DB，不影响功能。
 *
 * <p><b>A-3.16 · Dashboard 一致性语义</b>：Dashboard 聚合查询跨多表 join（job_instance / workflow_run /
 * file_record 等），PostgreSQL 默认 READ_COMMITTED 隔离级别下多表读之间可能 读到不同 MVCC 版本。TTL 10s
 * 窗口内缓存命中时读者看到同一快照；TTL 过期重算时可能读到 "正在写入中"的中间态（跨表 count 之和≠single-table total）。
 *
 * <p>这是**主动的设计权衡**：
 *
 * <ul>
 *   <li>Dashboard 是观察面板，秒级跨表误差可接受（对比 REPEATABLE_READ 带来的锁成本，性价比低）
 *   <li>需要强一致的场景请走带 tenant filter 的 single-table 查询
 *   <li>真要提升到事务快照：要么引 materialized view 定时刷，要么 @Transactional (isolation=REPEATABLE_READ,
 *       readOnly=true) 包 query chain —— 属于 v5 范围
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleQueryCacheService {

  private static final String PREFIX = "console:cache:";

  /** SCAN 单次返回上限 + DEL 单批次上限：避免一次 DEL 大列表阻塞 Redis 主线程。 */
  private static final int SCAN_BATCH_SIZE = 500;

  /** Meta 枚举（纯静态数据）。 */
  public static final Duration META_ENUM_TTL = Duration.ofMinutes(30);

  /** Meta 配置选项（队列/日历/窗口等，跟随租户配置变化���。 */
  public static final Duration META_OPTION_TTL = Duration.ofMinutes(5);

  /** Dashboard 汇总。 */
  public static final Duration DASHBOARD_TTL = Duration.ofSeconds(10);

  /** 调度快照。 */
  public static final Duration SNAPSHOT_TTL = Duration.ofSeconds(30);

  private final StringRedisTemplate redisTemplate;

  /**
   * 查询缓存：先读 Redis，miss 则执行 loader 并写入缓存。
   *
   * @param cacheKey 缓存键（自动加前缀）
   * @param ttl 过期时间
   * @param resultType 反序列化目标类型
   * @param loader 缓存未命中时的数据加载器
   */
  public <T> T getOrLoad(String cacheKey, Duration ttl, Class<T> resultType, Supplier<T> loader) {
    String fullKey = PREFIX + cacheKey;
    try {
      String cached = redisTemplate.opsForValue().get(fullKey);
      if (Texts.hasText(cached)) {
        return JsonUtils.fromJson(cached, resultType);
      }
    } catch (Exception e) {
      log.debug("cache read failed, falling back to db: key={}", fullKey, e);
    }
    T result = loader.get();
    try {
      if (result != null) {
        redisTemplate.opsForValue().set(fullKey, JsonUtils.toJson(result), ttl);
      }
    } catch (Exception e) {
      log.debug("cache write failed: key={}", fullKey, e);
    }
    return result;
  }

  /**
   * 按前缀清除缓存（配置变更时调用）。
   *
   * <p>使用 Redis SCAN（非 KEYS）增量扫描 + 分批 DEL，避免 KEYS 命令 O(N) 阻塞主线程。
   */
  public void evictByPrefix(String keyPrefix) {
    if (!Texts.hasText(keyPrefix)) {
      return;
    }
    String pattern = PREFIX + keyPrefix + "*";
    long deleted = RedisKeyUtils.scanAndDelete(redisTemplate, pattern, SCAN_BATCH_SIZE);
    if (deleted > 0) {
      log.debug("evicted {} cache keys with prefix: {}", deleted, keyPrefix);
    }
  }

  /** 清除指定租户的 meta 选项缓存。 */
  public void evictMetaOptions(String tenantId) {
    evictByPrefix("meta:" + tenantId);
  }

  /** 清除所有 meta 枚举缓存。 */
  public void evictMetaEnums() {
    evictByPrefix("meta:enums");
  }

  /** 清除指定租户的 dashboard 缓存。 */
  public void evictDashboard(String tenantId) {
    evictByPrefix("dashboard:" + tenantId);
  }
}
