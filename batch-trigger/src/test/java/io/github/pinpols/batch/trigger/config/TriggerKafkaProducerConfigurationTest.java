package io.github.pinpols.batch.trigger.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

class TriggerKafkaProducerConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(TriggerKafkaProducerConfiguration.class)
      .withBean(ObservationRegistry.class, () -> ObservationRegistry.NOOP)
      .withPropertyValues("spring.kafka.bootstrap-servers=broker:9092");

  @Test
  void createsProducerAndAdminInfrastructureWithoutKafkaAutoConfiguration() {
    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(ProducerFactory.class);
      assertThat(context).hasSingleBean(KafkaTemplate.class);
      assertThat(context).hasSingleBean(KafkaAdmin.class);
      assertThat(context.getBean(KafkaAdmin.class).getConfigurationProperties())
          .containsEntry(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092");
    });
  }
}
