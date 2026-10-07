package io.github.pinpols.batch.trigger.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@DisplayName("Trigger Kafka 生产者配置:脱离 Spring Boot Kafka 自动配置也能装配可用的生产端与 Admin 基础设施")
class TriggerKafkaProducerConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(TriggerKafkaProducerConfiguration.class)
      .withBean(ObservationRegistry.class, () -> ObservationRegistry.NOOP)
      .withPropertyValues(TriggerKafkaProducerConfiguration.BOOTSTRAP_SERVERS_KEY + "=broker:9092");

  @Test
  @DisplayName("只提供 bootstrap-servers 时,容器唯一装配 ProducerFactory、KafkaTemplate 与沿用该地址的 KafkaAdmin")
  void producerAndAdmin_areCreatedWithoutKafkaAutoConfiguration() {
    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(ProducerFactory.class);
      assertThat(context).hasSingleBean(KafkaTemplate.class);
      assertThat(context).hasSingleBean(KafkaAdmin.class);
      assertThat(context.getBean(KafkaAdmin.class).getConfigurationProperties())
          .containsEntry(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092");
    });
  }
}
