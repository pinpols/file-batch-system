package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfigurationGovernanceCatalogTest {

  @Test
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
