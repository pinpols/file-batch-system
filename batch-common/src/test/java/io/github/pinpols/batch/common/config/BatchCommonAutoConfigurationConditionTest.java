package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.health.BatchHealthAutoConfiguration;
import io.github.pinpols.batch.common.health.BatchStartupSelfCheck;
import io.github.pinpols.batch.common.health.HikariSaturationHealthIndicator;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
  private static final String KMS_TEST_KEY = "batch.security.kms.keys.DEFAULT_TEST="
      + Base64.getEncoder()
          .encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
  private static final String PROD_INTERNAL_SECRET =
      String.join("-", "prod", "internal", "test", "value");
  private static final String PROD_DB_PASSWORD =
      String.join("-", "prod", "database", "test", "value");

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
  void objectCryptoAutoConfigurationProvidesSecretPayloadProtector() {
    contextRunner
        .withConfiguration(AutoConfigurations.of(BatchObjectCryptoAutoConfiguration.class))
        .withPropertyValues(
            "spring.profiles.active=test", "batch.security.bypass-mode=true", KMS_TEST_KEY)
        .run(context -> {
          assertThat(context).hasSingleBean(BatchObjectCryptoService.class);
          assertThat(context).hasSingleBean(SecretPayloadProtector.class);
          assertThat(context).hasNotFailed();
        });
  }

  @Test
  void objectCryptoAutoConfigurationRejectsMissingDefaultKeyRef() {
    contextRunner
        .withConfiguration(AutoConfigurations.of(BatchObjectCryptoAutoConfiguration.class))
        .withPropertyValues(
            "spring.profiles.active=test",
            "batch.security.bypass-mode=true",
            "batch.security.kms.default-key-ref=missing",
            KMS_TEST_KEY)
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .hasMessageContaining("default-key-ref must reference an existing"));
  }

  @Test
  void objectCryptoAutoConfigurationRejectsInvalidAesKeyLength() {
    contextRunner
        .withConfiguration(AutoConfigurations.of(BatchObjectCryptoAutoConfiguration.class))
        .withPropertyValues(
            "spring.profiles.active=test",
            "batch.security.bypass-mode=true",
            "batch.security.kms.keys.DEFAULT_TEST=MTIz")
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .hasMessageContaining("must decode to a 16, 24, or 32 byte AES key"));
  }

  @Test
  void objectCryptoAutoConfigurationRejectsWeakProdKey() {
    contextRunner
        .withConfiguration(AutoConfigurations.of(BatchObjectCryptoAutoConfiguration.class))
        .withPropertyValues(
            "spring.profiles.active=prod",
            "batch.security.bypass-mode=false",
            "batch.security.internal-secret=" + PROD_INTERNAL_SECRET,
            "spring.datasource.password=" + PROD_DB_PASSWORD,
            "batch.security.kms.keys.DEFAULT_TEST=AAAAAAAAAAAAAAAAAAAAAA==")
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .hasMessageContaining("production batch.security.kms.keys.DEFAULT_TEST is weak"));
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
