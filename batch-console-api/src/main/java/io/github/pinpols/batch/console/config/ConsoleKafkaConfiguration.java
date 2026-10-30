package io.github.pinpols.batch.console.config;

import java.util.Map;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration(proxyBeanMethods = false)
@EnableKafka
/** 配置 Console 侧 Kafka 消费与序列化约定。 */
class ConsoleKafkaConfiguration {

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> terminalNotificationKafkaFactory(
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
      @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(Map.of(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        bootstrapServers,
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
        StringDeserializer.class,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
        StringDeserializer.class,
        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
        false,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
        "earliest",
        ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
        50)));
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.setAutoStartup(autoStartup);
    return factory;
  }

  @Bean
  KafkaAdmin kafkaAdmin(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
    return new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
  }
}
