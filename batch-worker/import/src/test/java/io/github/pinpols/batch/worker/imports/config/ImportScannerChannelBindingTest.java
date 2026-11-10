package io.github.pinpols.batch.worker.imports.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

@DisplayName("导入扫描器渠道绑定配置")
class ImportScannerChannelBindingTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
      .withUserConfiguration(BindingConfiguration.class);

  @Test
  @DisplayName("无渠道绑定时保留原有扫描配置")
  void shouldAllowNoBindings() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBean(ImportScannerProperties.class).getChannelBindings())
          .isEmpty();
    });
  }

  @Test
  @DisplayName("完整租户目录与渠道可成功绑定")
  void shouldBindChannel() {
    runner
        .withPropertyValues(
            "batch.worker.import.scanner.channel-bindings[0].tenant-id=tenant-a",
            "batch.worker.import.scanner.channel-bindings[0].object-prefix=ingress/tenant-a/bank/",
            "batch.worker.import.scanner.channel-bindings[0].channel-code=bank_in")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var binding = context
              .getBean(ImportScannerProperties.class)
              .getChannelBindings()
              .getFirst();
          assertThat(binding.getTenantId()).isEqualTo("tenant-a");
          assertThat(binding.getObjectPrefix()).isEqualTo("ingress/tenant-a/bank/");
          assertThat(binding.getChannelCode()).isEqualTo("bank_in");
        });
  }

  @Test
  @DisplayName("Helm 渲染的索引环境变量按 Spring 列表规则绑定")
  void shouldBindHelmEnvironmentVariables() {
    Map<String, Object> values = Map.of(
        "BATCH_WORKER_IMPORT_SCANNER_CHANNELBINDINGS_0_TENANTID", "tenant-a",
        "BATCH_WORKER_IMPORT_SCANNER_CHANNELBINDINGS_0_OBJECTPREFIX", "ingress/tenant-a/bank/",
        "BATCH_WORKER_IMPORT_SCANNER_CHANNELBINDINGS_0_CHANNELCODE", "BANK-SFTP");
    runner
        .withInitializer(context -> context
            .getEnvironment()
            .getPropertySources()
            .addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, values)))
        .run(context -> {
          assertThat(context).hasNotFailed();
          var binding = context
              .getBean(ImportScannerProperties.class)
              .getChannelBindings()
              .getFirst();
          assertThat(binding.getTenantId()).isEqualTo("tenant-a");
          assertThat(binding.getObjectPrefix()).isEqualTo("ingress/tenant-a/bank/");
          assertThat(binding.getChannelCode()).isEqualTo("BANK-SFTP");
        });
  }

  @Test
  @DisplayName("Compose 透传的 Spring JSON 实际绑定渠道列表")
  void shouldBindComposeSpringApplicationJson() {
    String json = """
        {"batch":{"worker":{"import":{"scanner":{"channel-bindings":[{
          "tenant-id":"tenant-a","object-prefix":"ingress/tenant-a/bank/",
          "channel-code":"BANK-SFTP"}]}}}}}
        """;
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .addFirst(new SystemEnvironmentPropertySource(
            "compose-fixture", Map.of("SPRING_APPLICATION_JSON", json)));
    SpringApplication application =
        new SpringApplication(BindingConfiguration.class, ValidationAutoConfiguration.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setEnvironment(environment);
    try (var context = application.run()) {
      var binding =
          context.getBean(ImportScannerProperties.class).getChannelBindings().getFirst();
      assertThat(binding.getTenantId()).isEqualTo("tenant-a");
      assertThat(binding.getObjectPrefix()).isEqualTo("ingress/tenant-a/bank/");
      assertThat(binding.getChannelCode()).isEqualTo("BANK-SFTP");
    }
  }

  @Test
  @DisplayName("渠道缺失、目录边界不完整或租户目录重复时启动失败")
  void shouldRejectInvalidBindings() {
    runner
        .withPropertyValues(
            "batch.worker.import.scanner.channel-bindings[0].tenant-id=tenant-a",
            "batch.worker.import.scanner.channel-bindings[0].object-prefix=ingress/tenant-a/bank/")
        .run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues(
            "batch.worker.import.scanner.channel-bindings[0].tenant-id=tenant-a",
            "batch.worker.import.scanner.channel-bindings[0].object-prefix=ingress/tenant-a/bank",
            "batch.worker.import.scanner.channel-bindings[0].channel-code=bank_in")
        .run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues(
            "batch.worker.import.scanner.channel-bindings[0].tenant-id=tenant-a",
            "batch.worker.import.scanner.channel-bindings[0].object-prefix=ingress/tenant-a/bank/",
            "batch.worker.import.scanner.channel-bindings[0].channel-code=bank_in",
            "batch.worker.import.scanner.channel-bindings[1].tenant-id=tenant-a",
            "batch.worker.import.scanner.channel-bindings[1].object-prefix=ingress/tenant-a/bank/",
            "batch.worker.import.scanner.channel-bindings[1].channel-code=bank_out")
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ImportScannerProperties.class)
  static class BindingConfiguration {}
}
