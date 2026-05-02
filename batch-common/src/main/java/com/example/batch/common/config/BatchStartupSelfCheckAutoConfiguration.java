package com.example.batch.common.config;

import com.example.batch.common.health.BatchStartupSelfCheck;
import com.example.batch.common.health.BatchStartupSelfCheckProperties;
import com.example.batch.common.mapper.InformationSchemaMapper;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(
    prefix = "batch.startup-self-check",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(BatchStartupSelfCheckProperties.class)
public class BatchStartupSelfCheckAutoConfiguration {

  @Bean
  public BatchStartupSelfCheck batchStartupSelfCheck(
      InformationSchemaMapper informationSchemaMapper,
      BatchStartupSelfCheckProperties properties,
      ObjectProvider<Flyway> flyway) {
    return new BatchStartupSelfCheck(informationSchemaMapper, properties, flyway);
  }
}
