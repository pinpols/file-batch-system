package com.example.batch.e2e.apps;

import com.example.batch.common.config.BatchClockConfig;
import com.example.batch.common.config.BatchJsonAutoConfiguration;
import com.example.batch.common.config.BatchObjectCryptoAutoConfiguration;
import com.example.batch.e2e.config.E2eKafkaProducerConfiguration;
import com.example.batch.e2e.config.E2ePlatformDataSourceConfiguration;
import com.example.batch.e2e.config.E2ePlatformMybatisConfiguration;
import com.example.batch.e2e.config.E2eShedLockConfiguration;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Orchestrator-only e2e application context. Scans only the orchestrator package to avoid conflicts
 * with worker modules on the test classpath. Used by tests that launch worker processes externally
 * (e.g. WorkerProcessRestartRecoveryE2eIT).
 */
@Configuration
@EnableAutoConfiguration(
    exclude = {
      com.example.batch.common.logging.HttpRequestMdcAutoConfiguration.class,
      org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class,
      org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration.class,
      org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration
          .class,
      org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class,
      org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration.class,
      org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration.class,
      org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
      org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class,
      org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration
          .class,
      org.springframework.boot.security.autoconfigure.web.servlet
          .ServletWebSecurityAutoConfiguration.class,
      org.springframework.boot.security.autoconfigure.actuate.web.servlet
          .ManagementWebSecurityAutoConfiguration.class,
    })
@EnableKafka
@Import({
  BatchClockConfig.class,
  E2ePlatformDataSourceConfiguration.class,
  E2ePlatformMybatisConfiguration.class,
  E2eShedLockConfiguration.class,
  E2eKafkaProducerConfiguration.class
})
@ComponentScan(
    basePackages = {"com.example.batch.e2e.support", "com.example.batch.orchestrator"},
    excludeFilters = {
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = BatchOrchestratorApplication.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = E2eOrchestratorApplication.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = com.example.batch.orchestrator.config.ShedLockConfiguration.class)
    })
@ImportAutoConfiguration({
  BatchJsonAutoConfiguration.class,
  BatchObjectCryptoAutoConfiguration.class,
  RestClientAutoConfiguration.class
})
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
@MapperScan(
    basePackages = "com.example.batch.common.mapper",
    sqlSessionFactoryRef = "sqlSessionFactory")
@MapperScan(
    basePackages = "com.example.batch.orchestrator.mapper",
    sqlSessionFactoryRef = "sqlSessionFactory")
public class E2eOrchestratorApplication {

  public static void main(String[] args) {
    SpringApplication.run(E2eOrchestratorApplication.class, args);
  }
}
