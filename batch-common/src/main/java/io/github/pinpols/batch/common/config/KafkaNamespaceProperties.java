package io.github.pinpols.batch.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/** 为同一部署的 Kafka topic 和消费组统一命名空间。 */
@Data
@ConfigurationProperties(prefix = "batch.kafka")
public class KafkaNamespaceProperties {

    /** 部署标识使用小写；仅非生产环境允许留空。 */
    private String namespace = "";
}
