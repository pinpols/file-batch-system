package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class S3AutoConfigurationTest {

  @Test
  void productionRejectsMissingCredentials() {
    S3StorageProperties properties = new S3StorageProperties();
    MockEnvironment environment = environmentWithProfile("prod");

    assertThatThrownBy(
            () -> S3AutoConfiguration.validateCredentialsInProduction(properties, environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("对象存储凭据未配置");
  }

  @Test
  void productionRejectsKnownMinioDefaults() {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setAccessKey("minioadmin");
    properties.setSecretKey("minioadmin123");
    MockEnvironment environment = environmentWithProfile("staging");

    assertThatThrownBy(
            () -> S3AutoConfiguration.validateCredentialsInProduction(properties, environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("已知 MinIO 默认凭据");
  }

  @Test
  void localAllowsDevelopmentCredentials() {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setAccessKey("minioadmin");
    properties.setSecretKey("minioadmin123");
    MockEnvironment environment = environmentWithProfile("local");

    assertThatCode(
            () -> S3AutoConfiguration.validateCredentialsInProduction(properties, environment))
        .doesNotThrowAnyException();
  }

  private static MockEnvironment environmentWithProfile(String profile) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profile);
    return environment;
  }
}
