package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.health.BatchStartupSelfCheck;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
}
