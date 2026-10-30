package io.github.pinpols.batch.e2e.apps;

import io.github.pinpols.batch.common.config.BatchClockConfig;
import io.github.pinpols.batch.common.config.BatchJsonAutoConfiguration;
import io.github.pinpols.batch.common.config.BatchObjectCryptoAutoConfiguration;
import io.github.pinpols.batch.common.logging.HttpRequestMdcAutoConfiguration;
import io.github.pinpols.batch.e2e.config.E2eBusinessDataSourceConfiguration;
import io.github.pinpols.batch.e2e.config.E2ePlatformDataSourceConfiguration;
import io.github.pinpols.batch.e2e.config.E2ePlatformMybatisConfiguration;
import io.github.pinpols.batch.e2e.config.E2eShedLockConfiguration;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.worker.atomic.BatchWorkerAtomicApplication;
import io.github.pinpols.batch.worker.dispatchs.BatchWorkerDispatchApplication;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchStepExecutionAdapter;
import io.github.pinpols.batch.worker.exports.BatchWorkerExportApplication;
import io.github.pinpols.batch.worker.exports.infrastructure.ExportStepExecutionAdapter;
import io.github.pinpols.batch.worker.imports.BatchWorkerImportApplication;
import io.github.pinpols.batch.worker.imports.infrastructure.ImportStepExecutionAdapter;
import io.github.pinpols.batch.worker.processes.BatchWorkerProcessApplication;
import io.github.pinpols.batch.worker.processes.infrastructure.ProcessStepExecutionAdapter;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 端到端测试用 ApplicationContext:orchestrator + worker-core + 专用原子任务 worker(batch-worker-atomic)同进程。
 *
 * <p>只扫 {@code io.github.pinpols.batch.worker.atomic}(不扫任何 pipeline worker 包),故没有 {@code @Primary}
 * 的 pipeline StepExecutionAdapter,worker-core 的 {@code DefaultStepExecutionAdapter} 作为唯一 adapter 把
 * SPI 任务按 payload.taskType 路由到对应执行器(此 e2e 只开 sql)。
 */
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
  E2eBusinessDataSourceConfiguration.class,
  E2ePlatformMybatisConfiguration.class,
  E2eShedLockConfiguration.class
})
@ComponentScan(
    basePackages = {
      "io.github.pinpols.batch.e2e.support",
      "io.github.pinpols.batch.common.spi.task",
      "io.github.pinpols.batch.orchestrator",
      "io.github.pinpols.batch.worker.core",
      "io.github.pinpols.batch.worker.atomic"
    },
    excludeFilters = {
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = BatchOrchestratorApplication.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = BatchWorkerImportApplication.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = BatchWorkerExportApplication.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = BatchWorkerProcessApplication.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = BatchWorkerDispatchApplication.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = BatchWorkerAtomicApplication.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = ImportStepExecutionAdapter.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = ExportStepExecutionAdapter.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = ProcessStepExecutionAdapter.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = DispatchStepExecutionAdapter.class)
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
@MapperScan(
    basePackages = "io.github.pinpols.batch.worker.core.mapper",
    sqlSessionFactoryRef = "sqlSessionFactory")
public class E2eAtomicApplication {

  public static void main(String[] args) {
    SpringApplication.run(E2eAtomicApplication.class, args);
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

  @Bean
  @Primary
  public PlatformTransactionManager transactionManager(
      @Qualifier("orchestratorPlatformDataSource") DataSource platformDataSource) {
    return new DataSourceTransactionManager(platformDataSource);
  }
}
