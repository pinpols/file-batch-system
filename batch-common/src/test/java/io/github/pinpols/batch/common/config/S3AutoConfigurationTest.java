package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("对象存储自动配置的生产凭据校验:凭据缺失与默认弱凭据拒绝,以及本地开发凭据放行")
class S3AutoConfigurationTest {

  @Test
  @DisplayName("生产环境未配置访问凭据时校验失败并提示凭据缺失")
  void shouldRejectMissingCredentials_whenProductionProfile() {
    S3StorageProperties properties = new S3StorageProperties();
    MockEnvironment environment = environmentWithProfile("prod");

    assertThatThrownBy(
            () -> S3AutoConfiguration.validateCredentialsInProduction(properties, environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("object-storage credentials are not configured");
  }

  @Test
  @DisplayName("预发布环境使用公开的默认管理凭据时校验失败并提示该凭据为已知默认值")
  void shouldRejectKnownDefaultCredentials_whenPreProductionProfile() {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setAccessKey("minioadmin");
    properties.setSecretKey("minioadmin123");
    MockEnvironment environment = environmentWithProfile("staging");

    assertThatThrownBy(
            () -> S3AutoConfiguration.validateCredentialsInProduction(properties, environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("known MinIO default credentials");
  }

  @Test
  @DisplayName("本地环境使用开发用默认凭据时校验通过")
  void shouldAllowDevelopmentCredentials_whenLocalProfile() {
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
