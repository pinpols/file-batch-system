package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.BatchClockConfig;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
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
  @Import({BatchClockConfig.class, OrchestratorRedisSupport.class})
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

    @SuppressWarnings("unchecked")
    Map<String, Object> result = redis.getJson(key, Map.class);
    assertThat(result).isNull();
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
  void evalLongExecutesLuaScriptAgainstRealRedis() {
    String key = "test:it:lua:" + System.nanoTime();
    redisTemplate.opsForValue().set(key, "99");

    Long result = redis.evalLong("return tonumber(redis.call('GET', KEYS[1]))", key);

    assertThat(result).isEqualTo(99L);
  }
}
