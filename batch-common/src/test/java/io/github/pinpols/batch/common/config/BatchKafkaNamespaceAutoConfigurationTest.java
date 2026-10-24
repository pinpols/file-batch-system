package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

@DisplayName("Kafka 模板的部署命名空间配置")
class BatchKafkaNamespaceAutoConfigurationTest {

  @DisplayName("模板键使用命名空间且不提前实例化配置 Bean")
  @Test
  void shouldNamespaceTemplateKeysWithoutCreatingConfigurationBeansEarly() {
    StandardEnvironment environment = new StandardEnvironment();
    TestPropertyValues.of("batch.kafka.namespace=blue").applyTo(environment);
    var postProcessor =
        BatchKafkaNamespaceAutoConfiguration.batchRedisNamespaceTemplatePostProcessor(environment);
    StringRedisTemplate template = new StringRedisTemplate();

    Object processed =
        postProcessor.postProcessAfterInitialization(template, "stringRedisTemplate");

    assertThat(processed).isSameAs(template);
    assertThat(serializeKey(template, "workflow:42")).isEqualTo("blue:workflow:42");
  }

  @DisplayName("未配置命名空间时保留本地模板键")
  @Test
  void shouldKeepLocalTemplateKeysUnchangedWhenNamespaceIsEmpty() {
    StandardEnvironment environment = new StandardEnvironment();
    var postProcessor =
        BatchKafkaNamespaceAutoConfiguration.batchRedisNamespaceTemplatePostProcessor(environment);
    StringRedisTemplate template = new StringRedisTemplate();

    postProcessor.postProcessAfterInitialization(template, "stringRedisTemplate");

    assertThat(serializeKey(template, "workflow:42")).isEqualTo("workflow:42");
  }

  @SuppressWarnings("unchecked")
  private static String serializeKey(StringRedisTemplate template, String key) {
    RedisSerializer<String> keySerializer = (RedisSerializer<String>) template.getKeySerializer();
    return new String(keySerializer.serialize(key), StandardCharsets.UTF_8);
  }
}
