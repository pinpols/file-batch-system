package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.health.BatchHealthAutoConfiguration;
import io.github.pinpols.batch.common.health.BatchStartupSelfCheck;
import io.github.pinpols.batch.common.health.HikariSaturationHealthIndicator;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.storage.EncryptingObjectStore;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.s3.S3Client;

@DisplayName("公共自动配置装配条件:存储后端互斥、启动自检开关、对象加密密钥校验与健康指示器数据源选择")
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
  @DisplayName("选择文件系统存储后端时不创建对象存储客户端,且上下文正常启动")
  void shouldBackOffObjectStorageAutoConfiguration_whenFilesystemBackendSelected() {
    contextRunner
        .withConfiguration(AutoConfigurations.of(S3AutoConfiguration.class))
        .withPropertyValues("batch.storage.backend=filesystem")
        .run(context -> {
          assertThat(context).doesNotHaveBean(S3Client.class);
          assertThat(context).hasNotFailed();
        });
  }

  @Test
  @DisplayName("显式关闭启动自检后不注册自检组件,且缺少数据库映射器也不会导致上下文失败")
  void shouldDisableStartupSelfCheck_whenExplicitlyTurnedOff() {
    contextRunner
        .withConfiguration(AutoConfigurations.of(BatchStartupSelfCheckAutoConfiguration.class))
        .withPropertyValues("batch.startup-self-check.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(BatchStartupSelfCheck.class);
          assertThat(context).hasNotFailed();
        });
  }

  @Test
  @DisplayName("测试环境开启对象加密后同时注册加解密服务与密文载荷保护器")
  void shouldProvideSecretPayloadProtector_whenCryptoEnabledInTestProfile() {
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
  @DisplayName("非旁路环境开启对象加密装饰后,业务注入的是加密对象存储而不是原始后端")
  void shouldDecorateObjectStore_whenEncryptionDecoratorEnabled() {
    contextRunner
        .withConfiguration(AutoConfigurations.of(
            BatchObjectCryptoAutoConfiguration.class, BatchObjectStoreAutoConfiguration.class))
        .withPropertyValues(
            "spring.profiles.active=test",
            "batch.storage.backend=filesystem",
            "batch.storage.filesystem.root=${java.io.tmpdir}/batch-object-store-config-test",
            "batch.storage.s3.endpoint=http://127.0.0.1:9000",
            "batch.storage.s3.bucket=batch-test",
            "batch.storage.startup-check.enabled=false",
            "batch.security.bypass-mode=false",
            "batch.storage.encryption.decorator-enabled=true",
            KMS_TEST_KEY)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasBean("rawObjectStore").hasBean("objectStore");
          assertThat(context).hasSingleBean(BatchObjectStore.class);
          assertThat(context.getBean(BatchObjectStore.class))
              .isInstanceOf(EncryptingObjectStore.class);
        });
  }

  @Test
  @DisplayName("租户提供完整对象存储实现时自动配置退避且不会暴露原始后端为业务候选")
  void shouldBackOffOuterObjectStore_whenCustomStoreProvided() {
    contextRunner
        .withUserConfiguration(CustomObjectStoreConfiguration.class)
        .withConfiguration(AutoConfigurations.of(BatchObjectStoreAutoConfiguration.class))
        .withPropertyValues(
            "spring.profiles.active=test",
            "batch.security.bypass-mode=true",
            "batch.storage.backend=filesystem",
            "batch.storage.filesystem.root=${java.io.tmpdir}/batch-object-store-custom-test",
            "batch.storage.s3.endpoint=http://127.0.0.1:9000",
            "batch.storage.s3.bucket=batch-test",
            "batch.storage.startup-check.enabled=false")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasBean("rawObjectStore");
          assertThat(context).hasSingleBean(BatchObjectStore.class);
          assertThat(context.getBean(BatchObjectStore.class))
              .isSameAs(context.getBean("customObjectStore"));
        });
  }

  @Test
  @DisplayName("默认密钥引用指向不存在的条目时上下文启动失败,并提示必须引用已存在的密钥")
  void shouldFailContext_whenDefaultKeyRefMissing() {
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
  @DisplayName("密钥解码后长度不是十六、二十四或三十二字节时上下文启动失败")
  void shouldFailContext_whenAesKeyLengthInvalid() {
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
  @DisplayName("生产环境使用强度不足的密钥时上下文启动失败,并指出该密钥强度偏弱")
  void shouldFailContext_whenProductionKeyWeak() {
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
  @DisplayName("存在多个数据源且未指定首选时退避不注册饱和健康指示器")
  void shouldBackOffHealthIndicator_whenMultipleDataSourcesWithoutPrimary() {
    contextRunner
        .withUserConfiguration(AmbiguousDataSourcesConfiguration.class)
        .withConfiguration(AutoConfigurations.of(BatchHealthAutoConfiguration.class))
        .run(context -> {
          assertThat(context).doesNotHaveBean(HikariSaturationHealthIndicator.class);
          assertThat(context).hasNotFailed();
        });
  }

  @Test
  @DisplayName("多数据源中指定首个为首选时正常注册饱和健康指示器")
  void shouldRegisterHealthIndicator_whenPrimaryDataSourcePresent() {
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

  @Configuration(proxyBeanMethods = false)
  static class CustomObjectStoreConfiguration {

    @Bean
    BatchObjectStore customObjectStore() {
      return mock(BatchObjectStore.class);
    }
  }
}
