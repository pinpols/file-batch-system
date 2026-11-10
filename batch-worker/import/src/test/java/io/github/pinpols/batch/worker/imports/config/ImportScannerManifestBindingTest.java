package io.github.pinpols.batch.worker.imports.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

@DisplayName("导入扫描器权威清单绑定配置")
class ImportScannerManifestBindingTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
      .withUserConfiguration(BindingConfiguration.class);

  @Test
  @DisplayName("租户目录、文件组和带业务日的权威清单键可绑定")
  void manifestBinding_shouldBindValidLocation() {
    runner
        .withPropertyValues(
            "batch.worker.import.scanner.manifest-bindings[0].tenant-id=t1",
            "batch.worker.import.scanner.manifest-bindings[0].object-prefix=ingress/t1/orders/",
            "batch.worker.import.scanner.manifest-bindings[0].file-group-code=ORDERS",
            "batch.worker.import.scanner.manifest-bindings[0].manifest-key-template=manifests/t1/orders-{bizDateCompact}.batch.json")
        .run(context -> {
          assertThat(context).hasNotFailed();
          ImportScannerProperties.ManifestBinding binding = context
              .getBean(ImportScannerProperties.class)
              .getManifestBindings()
              .getFirst();
          assertThat(binding.getFileGroupCode()).isEqualTo("ORDERS");
          assertThat(binding.getManifestKeyTemplate()).contains("{bizDateCompact}");
        });
  }

  @Test
  @DisplayName("缺失业务日占位符、目录越界或重复绑定不能启动")
  void manifestBinding_shouldRejectAmbiguousOrInvalidBinding() {
    String tenant = "batch.worker.import.scanner.manifest-bindings[0].tenant-id=t1";
    String group = "batch.worker.import.scanner.manifest-bindings[0].file-group-code=ORDERS";
    String directory =
        "batch.worker.import.scanner.manifest-bindings[0].object-prefix=ingress/t1/orders/";
    runner
        .withPropertyValues(
            tenant,
            group,
            directory,
            "batch.worker.import.scanner.manifest-bindings[0].manifest-key-template=manifests/t1/orders.batch.json")
        .run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues(
            tenant,
            group,
            "batch.worker.import.scanner.manifest-bindings[0].object-prefix=other/t1/orders/",
            "batch.worker.import.scanner.manifest-bindings[0].manifest-key-template=manifests/t1/orders-{bizDate}.batch.json")
        .run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues(
            tenant,
            group,
            directory,
            "batch.worker.import.scanner.manifest-bindings[0].manifest-key-template=manifests/t1/orders-{bizDate}.batch.json",
            "batch.worker.import.scanner.manifest-bindings[1].tenant-id=t1",
            "batch.worker.import.scanner.manifest-bindings[1].object-prefix=ingress/t1/orders/",
            "batch.worker.import.scanner.manifest-bindings[1].file-group-code=OTHER",
            "batch.worker.import.scanner.manifest-bindings[1].manifest-key-template=manifests/t1/other-{bizDate}.batch.json")
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ImportScannerProperties.class)
  static class BindingConfiguration {}
}
