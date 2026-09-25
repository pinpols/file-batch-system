package io.github.pinpols.batch.console.support.ratelimit;

/** 限流窗口存储端口。 */
public interface RateLimitStore {

  boolean tryAcquire(
      String key, long now, long windowStart, int limit, String member, long ttlSeconds);
}
