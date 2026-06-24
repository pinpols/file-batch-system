package io.github.pinpols.batch.orchestrator.security;

import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 基于 Redis SETNX(setIfAbsent)+TTL 的 nonce 一次性存储，集群级生效。 */
@Component
@RequiredArgsConstructor
public class RedisNonceStore implements NonceStore {

  private final OrchestratorRedisSupport redis;

  @Override
  public boolean registerIfAbsent(String tenantId, String nonce, Duration ttl) {
    String key = "sig:nonce:" + tenantId + ':' + nonce;
    Boolean firstWrite = redis.redisTemplate().opsForValue().setIfAbsent(key, "1", ttl);
    return Boolean.TRUE.equals(firstWrite);
  }
}
