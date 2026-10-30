package io.github.pinpols.batch.e2e.apps;

import io.github.pinpols.batch.common.config.BatchClockConfig;
import io.github.pinpols.batch.common.config.BatchJsonAutoConfiguration;
import io.github.pinpols.batch.common.config.BatchObjectCryptoAutoConfiguration;
import io.github.pinpols.batch.common.logging.HttpRequestMdcAutoConfiguration;
import io.github.pinpols.batch.e2e.config.E2eKafkaProducerConfiguration;
import io.github.pinpols.batch.e2e.config.E2ePlatformDataSourceConfiguration;
import io.github.pinpols.batch.e2e.config.E2ePlatformMybatisConfiguration;
import io.github.pinpols.batch.e2e.config.E2eShedLockConfiguration;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.config.WorkflowSensorDataSourceConfiguration;
import io.github.pinpols.batch.orchestrator.infrastructure.sensor.DbRowExistsSensorPolicy;
import java.util.concurrent.Executor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 不得修改任一租约，也不得注入第二条 Kafka 消息来强制恢复。 */
@Configuration
@EnableAutoConfiguration(
    exclude = {
      HttpRequestMdcAutoConfiguration.class,
      AnthropicChatAutoConfiguration.class,
      OpenAiChatAutoConfiguration.class,
      OpenAiAudioSpeechAutoConfiguration.class,
      OpenAiAudioTranscriptionAutoConfiguration.class,
      OpenAiEmbeddingAutoConfiguration.class,
      OpenAiImageAutoConfiguration.class,
      OpenAiModerationAutoConfiguration.class,
      SecurityAutoConfiguration.class,
      UserDetailsServiceAutoConfiguration.class,
      SecurityFilterAutoConfiguration.class,
      ServletWebSecurityAutoConfiguration.class,
      ManagementWebSecurityAutoConfiguration.class,
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
    basePackages = {
      "io.github.pinpols.batch.e2e.support",
      "io.github.pinpols.batch.common.spi.task",
      "io.github.pinpols.batch.orchestrator"
    },
    excludeFilters = {
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = BatchOrchestratorApplication.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = E2eOrchestratorApplication.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = WorkflowSensorDataSourceConfiguration.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = DbRowExistsSensorPolicy.class)
    })
@ImportAutoConfiguration({
  BatchJsonAutoConfiguration.class,
  BatchObjectCryptoAutoConfiguration.class,
  RestClientAutoConfiguration.class
})
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "io.github.pinpols.batch")
@MapperScan(
    basePackages = "io.github.pinpols.batch.common.mapper",
    sqlSessionFactoryRef = "sqlSessionFactory")
@MapperScan(
    basePackages = "io.github.pinpols.batch.orchestrator.mapper",
    sqlSessionFactoryRef = "sqlSessionFactory")
public class E2eReportRecoveryOrchestratorApplication {

  public static void main(String[] args) {
    SpringApplication.run(E2eReportRecoveryOrchestratorApplication.class, args);
  }

  @Bean(name = "applicationTaskExecutor")
  public Executor applicationTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setThreadNamePrefix("e2e-app-task-");
    executor.initialize();
    return executor;
  }
}
