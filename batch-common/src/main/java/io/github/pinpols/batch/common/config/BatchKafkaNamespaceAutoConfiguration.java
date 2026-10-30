package io.github.pinpols.batch.common.config;

import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.redis.BatchRedisNamespace;
import io.github.pinpols.batch.common.redis.BatchRedisNamespaceSerializer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 将 Kafka Topic 与 Redis key 绑定到同一批量命名空间，避免多个部署共享基础设施时相互覆盖。 */
@AutoConfiguration
@EnableConfigurationProperties(KafkaNamespaceProperties.class)
public class BatchKafkaNamespaceAutoConfiguration {

  @Bean
  public BatchKafkaTopicNamespace batchKafkaTopicNamespace(KafkaNamespaceProperties properties) {
    return new BatchKafkaTopicNamespace(properties.getNamespace());
  }

  @Bean
  public BatchRedisNamespace batchRedisNamespace(KafkaNamespaceProperties properties) {
    return new BatchRedisNamespace(properties.getNamespace());
  }

  @Bean
  public static BeanPostProcessor batchRedisNamespaceTemplatePostProcessor(
      Environment environment) {
    String namespaceValue =
        Binder.get(environment).bind("batch.kafka.namespace", String.class).orElse("");
    BatchRedisNamespace namespace = new BatchRedisNamespace(namespaceValue);
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof StringRedisTemplate template && namespace.isNamespaced()) {
          template.setKeySerializer(new BatchRedisNamespaceSerializer(namespace));
        }
        return bean;
      }
    };
  }
}
