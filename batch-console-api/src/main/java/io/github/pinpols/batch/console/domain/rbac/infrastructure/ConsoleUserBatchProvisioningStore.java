package io.github.pinpols.batch.console.domain.rbac.infrastructure;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/** 批量开户预览的短期 Redis 存储适配器；提交记录由 MyBatis 管理。 */
@Repository
@RequiredArgsConstructor
public class ConsoleUserBatchProvisioningStore {

  private static final DefaultRedisScript<Long> CAS = new DefaultRedisScript<>("""
      if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
      redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
      return 1
      """, Long.class);
  private static final DefaultRedisScript<Long> DELETE = new DefaultRedisScript<>("""
      if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
      return 0
      """, Long.class);

  private final StringRedisTemplate redis;

  public void savePreview(String key, String json, Duration ttl) {
    redis.opsForValue().set(key, json, ttl);
  }

  public String loadPreview(String key) {
    return redis.opsForValue().get(key);
  }

  /** 对读取到的完整快照做 CAS，版本与操作者校验不能与写入分离。 */
  public boolean replacePreview(String key, String expected, String replacement, Duration ttl) {
    return Long.valueOf(1)
        .equals(
            redis.execute(CAS, List.of(key), expected, replacement, Long.toString(ttl.toMillis())));
  }

  public void deletePreview(String key, String expected) {
    redis.execute(DELETE, List.of(key), expected);
  }
}
