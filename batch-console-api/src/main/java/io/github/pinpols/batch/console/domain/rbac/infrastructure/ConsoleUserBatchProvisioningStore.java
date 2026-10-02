package io.github.pinpols.batch.console.domain.rbac.infrastructure;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/** 批量开户预览的短期 Redis 存储适配器；提交记录由 MyBatis 管理。 */
@Repository
@RequiredArgsConstructor
public class ConsoleUserBatchProvisioningStore {

  private final StringRedisTemplate redis;

  public void savePreview(String key, String json, Duration ttl) {
    redis.opsForValue().set(key, json, ttl);
  }

  public String loadPreview(String key) {
    return redis.opsForValue().get(key);
  }
}
