package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("配置治理目录: 生成条目的字段完整性与敏感度标注")
class ConfigurationGovernanceCatalogTest {

  @Test
  @DisplayName("加载生成的运行时配置目录后,条目字段齐备且存在敏感项")
  void shouldLoadGeneratedRuntimeCatalog() {
    ConfigurationGovernanceCatalog catalog = new ConfigurationGovernanceCatalog();

    assertThat(catalog.items()).isNotEmpty().allSatisfy(item -> {
      assertThat(item.id()).isNotBlank();
      assertThat(item.className()).isNotBlank();
      assertThat(item.prefix()).isNotBlank();
      assertThat(item.source()).isEqualTo("STATIC");
      assertThat(item.activation()).isEqualTo("RESTART_REQUIRED");
      assertThat(item.restartRequired()).isTrue();
    });
    assertThat(catalog.items())
        .anySatisfy(item -> assertThat(item.sensitivity()).isEqualTo("SECRET"));
  }
}
