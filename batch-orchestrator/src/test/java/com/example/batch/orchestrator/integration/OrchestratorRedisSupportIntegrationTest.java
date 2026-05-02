package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 集成测试：验证 OrchestratorRedisSupport 的核心 Redis 操作使用真实 Redis 容器正确执行。 */
@SpringBootTest(
    classes = OrchestratorRedisSupportIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"batch.startup-self-check.enabled=false"})
class OrchestratorRedisSupportIntegrationTest extends AbstractIntegrationTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(OrchestratorRedisSupport.class)
  static class TestApplication {}

  @Autowired private OrchestratorRedisSupport redis;

  @Autowired private StringRedisTemplate redisTemplate;

  @Test
  void setJsonAndGetJsonRoundTrip() {
    String key = "test:it:json:" + System.nanoTime();
    Map<String, Object> payload = Map.of("name", "hello", "count", 42);

    redis.setJson(key, payload, Duration.ofMinutes(1));

    @SuppressWarnings("unchecked")
    Map<String, Object> result = redis.getJson(key, Map.class);
    assertThat(result).isNotNull();
    assertThat(result.get("name")).isEqualTo("hello");
    assertThat(((Number) result.get("count")).intValue()).isEqualTo(42);
  }

  @Test
  void getJsonReturnsNullAfterDelete() {
    String key = "test:it:delete:" + System.nanoTime();
    redis.setJson(key, Map.of("x", "y"), Duration.ofMinutes(1));

    redis.delete(key);

    assertThat(redis.getJson(key, Map.class)).isNull();
  }

  @Test
  void putHashAllAndEntriesRoundTripWithTtl() {
    String key = "test:it:hash:" + System.nanoTime();
    Map<String, String> fields = Map.of("k1", "v1", "k2", "v2");

    redis.putHashAll(key, fields, Duration.ofMinutes(1));

    Map<Object, Object> entries = redis.entries(key);
    assertThat(entries).containsEntry("k1", "v1").containsEntry("k2", "v2");
    assertThat(redisTemplate.getExpire(key)).isPositive();
  }

  @Test
  void incrementWithinWindowCountsAndSetsTtlOnFirstIncrement() {
    String tenantId = "t-rate-" + System.nanoTime();
    long window = 1_000_000L;
    Duration ttl = Duration.ofMinutes(1);

    Long first = redis.incrementWithinWindow(tenantId, "export", window, ttl);
    Long second = redis.incrementWithinWindow(tenantId, "export", window, ttl);
    Long third = redis.incrementWithinWindow(tenantId, "export", window, ttl);

    assertThat(first).isEqualTo(1L);
    assertThat(second).isEqualTo(2L);
    assertThat(third).isEqualTo(3L);

    // Key 应有 TTL（仅在首次递增时设置）
    String key = "ratelimit:" + tenantId.replace(':', '_') + ":export:" + window;
    assertThat(redisTemplate.getExpire(key)).isPositive();
  }

  @Test
  void evalLongExecutesLuaScriptAgainstRealRedis() {
    String key = "test:it:lua:" + System.nanoTime();
    redisTemplate.opsForValue().set(key, "99");

    Long result = redis.evalLong("return tonumber(redis.call('GET', KEYS[1]))", key);

    assertThat(result).isEqualTo(99L);
  }
}
