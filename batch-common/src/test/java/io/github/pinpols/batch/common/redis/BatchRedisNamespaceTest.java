package io.github.pinpols.batch.common.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.config.BatchKafkaNamespaceAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("Redis 键、模式与通道的部署隔离")
class BatchRedisNamespaceTest {

  @DisplayName("键模式和通道使用前缀并可还原逻辑键")
  @Test
  void shouldPrefixKeysPatternsAndChannelsAndRoundTripLogicalKeys() {
    BatchRedisNamespace namespace = new BatchRedisNamespace("blue");
    BatchRedisNamespaceSerializer serializer = new BatchRedisNamespaceSerializer(namespace);

    assertThat(namespace.key("cache:item")).isEqualTo("blue:cache:item");
    assertThat(namespace.scanPattern("cache:*")).isEqualTo("blue:cache:*");
    assertThat(namespace.channel("events")).isEqualTo("blue:events");
    assertThat(serializer.deserialize(serializer.serialize("cache:item"))).isEqualTo("cache:item");
  }

  @DisplayName("单部署保持原 Redis 键")
  @Test
  void shouldPreserveSingleDeploymentKeys() {
    BatchRedisNamespace namespace = new BatchRedisNamespace("");

    assertThat(namespace.key("cache:item")).isEqualTo("cache:item");
    assertThat(namespace.scanPattern("cache:*")).isEqualTo("cache:*");
    assertThat(namespace.channel("events")).isEqualTo("events");
  }

  @DisplayName("字符串 Redis 模板应用命名空间序列化器")
  @Test
  void shouldApplySerializerToStringRedisTemplates() {
    StringRedisTemplate template = new StringRedisTemplate();

    Object configured =
        BatchKafkaNamespaceAutoConfiguration.batchRedisNamespaceTemplatePostProcessor(
                new MockEnvironment().withProperty("batch.kafka.namespace", "blue"))
            .postProcessAfterInitialization(template, "stringRedisTemplate");

    assertThat(configured).isSameAs(template);
    assertThat(template.getKeySerializer()).isInstanceOf(BatchRedisNamespaceSerializer.class);
    assertThatThrownBy(() -> new BatchRedisNamespace("../blue"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
