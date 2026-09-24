package io.github.pinpols.batch.e2e.apps;

import io.github.pinpols.batch.common.config.BatchClockConfig;
import io.github.pinpols.batch.common.config.BatchJsonAutoConfiguration;
import io.github.pinpols.batch.common.config.BatchObjectCryptoAutoConfiguration;
import io.github.pinpols.batch.e2e.config.E2eKafkaProducerConfiguration;
import io.github.pinpols.batch.e2e.config.E2ePlatformDataSourceConfiguration;
import io.github.pinpols.batch.e2e.config.E2ePlatformMybatisConfiguration;
import io.github.pinpols.batch.e2e.config.E2eShedLockConfiguration;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import java.util.concurrent.Executor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * ADR-010 Stage 5 trigger-only E2E application context. 与 {@link
 * E2eOrchestratorApplication} 同款风格,只 scan trigger 包,避免 worker / orchestrator 包冲突。
 *
 * <p>{@code TriggerAsyncLaunchFullChainE2eIT} 会在 orchestrator 测试上下文旁启动本上下文，共享真实 PG/Kafka，验证
 * trigger service → trigger outbox relay → Kafka → orchestrator consumer → job_instance 的完整链路。
 */
@Configuration
@EnableAutoConfiguration(
    exclude = {
      io.github.pinpols.batch.common.config.BatchObjectStoreAutoConfiguration.class,
      io.github.pinpols.batch.common.config.S3AutoConfiguration.class,
      io.github.pinpols.batch.common.logging.HttpRequestMdcAutoConfiguration.class,
      org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration.class,
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
    basePackages = {"io.github.pinpols.batch.common.spi.task", "io.github.pinpols.batch.trigger"},
    excludeFilters = {
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = BatchTriggerApplication.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = E2eTriggerApplication.class)
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
    basePackages = "io.github.pinpols.batch.trigger.mapper",
    sqlSessionFactoryRef = "sqlSessionFactory")
public class E2eTriggerApplication {

  public static void main(String[] args) {
    SpringApplication.run(E2eTriggerApplication.class, args);
  }

  // KafkaOutboxPublisher 需要 @Qualifier("applicationTaskExecutor") Executor;
  // 此 slice 下 TaskExecutionAutoConfiguration 未触发,显式补一个最小线程池。
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
