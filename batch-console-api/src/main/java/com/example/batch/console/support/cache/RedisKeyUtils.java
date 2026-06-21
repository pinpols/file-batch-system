package com.example.batch.console.support.cache;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 键集合操作通用工具:抽走 console-api 内重复的 SCAN-based 模式删除样板 （{@link ConsoleQueryCacheService} 与 {@link
 * com.example.batch.console.infrastructure.config.ConsoleConfigCacheInvalidationService}）。
 *
 * <p><b>为什么不用 KEYS:</b> Redis {@code KEYS pattern} 是 O(N) 阻塞主线程命令,大库 100k+ key 时可阻塞主线程 秒级,生产严禁。SCAN
 * 是 cursor 增量扫描,每轮只返回 ~count 个 key,主线程被阻塞时间可控。
 *
 * <p><b>为什么分批 DEL:</b> 一次 {@code DEL k1 k2 ... kN} N 越大主线程阻塞越久;500 是经验折中:太小 SCAN 轮次多额外网络 RTT,太大单次
 * DEL 阻塞过久。
 *
 * <p><b>异常隔离:</b> 单次 SCAN/DEL 抛 {@link RuntimeException} 内捕获并抑制(由调用方记日志);残余 key 会在下次 evict 或 TTL
 * 过期时自然清理。这里**不**记日志/抛异常 — 工具类不掺杂业务可观测性,调用方负责打 warn/debug。
 *
 * <p>放在 console-api 而非 batch-common:batch-common 没引 spring-data-redis(避免下游模块强绑定),保留在 调用方所在模块。
 */
public final class RedisKeyUtils {

  private RedisKeyUtils() {}

  /**
   * 按 pattern 增量 SCAN + 分批 DEL,返回成功删除的 key 总数。pattern 为 null/blank 时直接返回 0。
   *
   * @param redisTemplate Redis 模板
   * @param pattern Redis glob pattern,例如 {@code "console:cache:meta:*"}
   * @param batchSize 单次 SCAN count + 单次 DEL 上限,推荐 500
   * @return 成功删除的 key 数;SCAN/DEL 失败返回已成功删除的累计数(不抛)
   */
  public static long scanAndDelete(
      StringRedisTemplate redisTemplate, String pattern, int batchSize) {
    if (pattern == null || pattern.isBlank()) {
      return 0L;
    }
    int safeBatch = batchSize <= 0 ? 500 : batchSize;
    ScanOptions options = ScanOptions.scanOptions().match(pattern).count(safeBatch).build();
    long deleted = 0L;
    try (Cursor<String> cursor = redisTemplate.scan(options)) {
      List<String> batch = new ArrayList<>(safeBatch);
      while (cursor.hasNext()) {
        batch.add(cursor.next());
        if (batch.size() >= safeBatch) {
          deleted += deleteBatch(redisTemplate, batch);
          batch.clear();
        }
      }
      if (!batch.isEmpty()) {
        deleted += deleteBatch(redisTemplate, batch);
      }
    } catch (RuntimeException ex) {
      // 工具层吞异常,调用方负责日志输出;残余 key 走自然 TTL 兜底。
      return deleted;
    }
    return deleted;
  }

  private static long deleteBatch(StringRedisTemplate redisTemplate, List<String> keys) {
    Long n = redisTemplate.delete(keys);
    return n == null ? 0L : n;
  }
}
