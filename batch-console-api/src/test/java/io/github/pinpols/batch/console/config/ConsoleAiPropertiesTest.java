package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ConsoleAiPropertiesTest {

  @Test
  void validProvider_shouldBindCaseInsensitively() {
    ConsoleAiProperties properties = bind("openai");

    assertThat(properties.getProvider()).isEqualTo(ConsoleAiProperties.Provider.OPENAI);
  }

  @Test
  void misspelledProvider_shouldFailBinding() {
    assertThatThrownBy(() -> bind("opeani"))
        .hasMessageContaining("batch.console.ai.provider")
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  private static ConsoleAiProperties bind(String provider) {
    Binder binder =
        new Binder(
            new MapConfigurationPropertySource(Map.of("batch.console.ai.provider", provider)));
    return binder.bind("batch.console.ai", Bindable.of(ConsoleAiProperties.class)).get();
  }
}
