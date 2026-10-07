package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.BatchClockConfig;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("Redis 支撑组件在真实容器中的核心读写行为,验证结构化载荷往返,删除后读取为空,哈希批量写入带有效期以及脚本求值")
class OrchestratorRedisSupportIntegrationTest extends AbstractIntegrationTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({BatchClockConfig.class, OrchestratorRedisSupport.class})
  static class TestApplication {}

  @Autowired
  private OrchestratorRedisSupport redis;

  @Autowired
  private StringRedisTemplate redisTemplate;

  @Test
  @DisplayName("写入结构化载荷后可原样读回,文本字段与数值字段取值一致")
  void shouldRoundTripJsonPayload_whenReadBackImmediately() {
    String key = "test:it:json:" + System.nanoTime();
    Map<String, Object> payload = Map.of("name", "hello", "count", 42);

    redis.setJson(key, payload, Duration.ofMinutes(1));

    @SuppressWarnings("unchecked")
    Map<String, Object> result = redis.getJson(key, Map.class);
    assertThat(result).isNotNull();
    assertThat(result).containsEntry("name", "hello");
    assertThat(((Number) result.get("count")).intValue()).isEqualTo(42);
  }

  @Test
  @DisplayName("删除键之后再读取返回空,不残留历史值")
  void shouldReturnNull_whenReadingDeletedKey() {
    String key = "test:it:delete:" + System.nanoTime();
    redis.setJson(key, Map.of("x", "y"), Duration.ofMinutes(1));

    redis.delete(key);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = redis.getJson(key, Map.class);
    assertThat(result).isNull();
  }

  @Test
  @DisplayName("批量写入哈希字段后可读回全部条目,且键的剩余有效期为正")
  void shouldRoundTripHashEntries_whenTtlConfigured() {
    String key = "test:it:hash:" + System.nanoTime();
    Map<String, String> fields = Map.of("k1", "v1", "k2", "v2");

    redis.putHashAll(key, fields, Duration.ofMinutes(1));

    Map<Object, Object> entries = redis.entries(key);
    assertThat(entries).containsEntry("k1", "v1").containsEntry("k2", "v2");
    assertThat(redisTemplate.getExpire(key)).isPositive();
  }

  @Test
  @DisplayName("对真实 Redis 执行脚本求值,返回可解析为长整型的数值结果")
  void shouldReturnNumericResult_whenEvaluatingScript() {
    String key = "test:it:lua:" + System.nanoTime();
    redisTemplate.opsForValue().set(key, "99");

    Long result = redis.evalLong("return tonumber(redis.call('GET', KEYS[1]))", key);

    assertThat(result).isEqualTo(99L);
  }
}
