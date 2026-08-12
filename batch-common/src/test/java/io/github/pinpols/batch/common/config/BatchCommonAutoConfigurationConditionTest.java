package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.health.BatchHealthAutoConfiguration;
import io.github.pinpols.batch.common.health.BatchStartupSelfCheck;
import io.github.pinpols.batch.common.health.HikariSaturationHealthIndicator;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.s3.S3Client;

class BatchCommonAutoConfigurationConditionTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  @Test
  void s3AutoConfigurationBacksOffWhenFilesystemBackendIsSelected() {
    contextRunner
        .withConfiguration(AutoConfigurations.of(S3AutoConfiguration.class))
        .withPropertyValues("batch.storage.backend=filesystem")
        .run(context -> {
          assertThat(context).doesNotHaveBean(S3Client.class);
          assertThat(context).hasNotFailed();
        });
  }

  @Test
  void startupSelfCheckCanBeDisabledWithoutDatabaseMapper() {
    contextRunner
        .withConfiguration(AutoConfigurations.of(BatchStartupSelfCheckAutoConfiguration.class))
        .withPropertyValues("batch.startup-self-check.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(BatchStartupSelfCheck.class);
          assertThat(context).hasNotFailed();
        });
  }

  @Test
  void hikariSaturationHealthIndicatorBacksOffForAmbiguousDataSources() {
    contextRunner
        .withUserConfiguration(AmbiguousDataSourcesConfiguration.class)
        .withConfiguration(AutoConfigurations.of(BatchHealthAutoConfiguration.class))
        .run(context -> {
          assertThat(context).doesNotHaveBean(HikariSaturationHealthIndicator.class);
          assertThat(context).hasNotFailed();
        });
  }

  @Test
  void hikariSaturationHealthIndicatorUsesPrimaryDataSourceWhenMultipleExist() {
    contextRunner
        .withUserConfiguration(PrimaryDataSourceConfiguration.class)
        .withConfiguration(AutoConfigurations.of(BatchHealthAutoConfiguration.class))
        .run(context -> {
          assertThat(context).hasSingleBean(HikariSaturationHealthIndicator.class);
          assertThat(context).hasNotFailed();
        });
  }

  @Configuration(proxyBeanMethods = false)
  static class AmbiguousDataSourcesConfiguration {

    @Bean
    DataSource firstDataSource() {
      return mock(DataSource.class);
    }

    @Bean
    DataSource secondDataSource() {
      return mock(DataSource.class);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class PrimaryDataSourceConfiguration {

    @Bean
    @Primary
    DataSource primaryDataSource() {
      return mock(DataSource.class);
    }

    @Bean
    DataSource secondaryDataSource() {
      return mock(DataSource.class);
    }
  }
}
