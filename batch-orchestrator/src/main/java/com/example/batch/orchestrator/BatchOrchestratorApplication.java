package com.example.batch.orchestrator;

import com.example.batch.common.config.BatchJsonAutoConfiguration;
import com.example.batch.common.config.BatchObjectCryptoAutoConfiguration;
import com.example.batch.common.config.BatchStartupSelfCheckAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@SpringBootApplication(
        scanBasePackages = "com.example.batch",
        exclude = {DataRedisRepositoriesAutoConfiguration.class})
@ImportAutoConfiguration({
        BatchJsonAutoConfiguration.class,
        BatchObjectCryptoAutoConfiguration.class,
        BatchStartupSelfCheckAutoConfiguration.class,
        RestClientAutoConfiguration.class
})
@MapperScan("com.example.batch.orchestrator.mapper")
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
@EnableScheduling
public class BatchOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchOrchestratorApplication.class, args);
    }

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
