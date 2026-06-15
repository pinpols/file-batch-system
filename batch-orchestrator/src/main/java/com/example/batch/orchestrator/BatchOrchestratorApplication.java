package com.example.batch.orchestrator;

import com.example.batch.common.config.BatchJsonAutoConfiguration;
import com.example.batch.common.config.BatchKafkaProducerProperties;
import com.example.batch.common.config.BatchKafkaProducerSupport;
import com.example.batch.common.config.BatchObjectCryptoAutoConfiguration;
import com.example.batch.common.config.BatchStartupSelfCheckAutoConfiguration;
import io.micrometer.observation.ObservationRegistry;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    scanBasePackages = "com.example.batch",
    exclude = {DataRedisRepositoriesAutoConfiguration.class})
@ImportAutoConfiguration({
  BatchJsonAutoConfiguration.class,
  BatchObjectCryptoAutoConfiguration.class,
  BatchStartupSelfCheckAutoConfiguration.class,
  RestClientAutoConfiguration.class
})
@MapperScan({
  "com.example.batch.orchestrator.mapper",
  "com.example.batch.orchestrator.auth",
  "com.example.batch.common.mapper"
})
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
@EnableScheduling
public class BatchOrchestratorApplication {

  public static void main(String[] args) {
    SpringApplication.run(BatchOrchestratorApplication.class, args);
  }

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  // outbox 发布路径 producer:统一走全局 spring.kafka.producer.* 调优(acks=all / 幂等 / delivery
  // & request 超时 / buffer.memory / max.block 背压),不再只设 bootstrap+serializer 而丢掉这些保护。
  @Bean
  public ProducerFactory<String, String> producerFactory(
      BatchKafkaProducerProperties kafkaProducerProperties) {
    return new DefaultKafkaProducerFactory<>(
        BatchKafkaProducerSupport.stringProducerConfig(bootstrapServers, kafkaProducerProperties));
  }

  @Bean
  public KafkaTemplate<String, String> kafkaTemplate(
      ProducerFactory<String, String> producerFactory, ObservationRegistry observationRegistry) {
    KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
    template.setObservationEnabled(true);
    template.setObservationRegistry(observationRegistry);
    return template;
  }
}
