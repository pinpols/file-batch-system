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
  void openAiCompatibleProvider_shouldBindWithHyphenatedValue() {
    ConsoleAiProperties properties = bind("openai-compatible");

    assertThat(properties.getProvider()).isEqualTo(ConsoleAiProperties.Provider.OPENAI_COMPATIBLE);
  }

  @Test
  void crossProviderFailover_shouldBeDisabledByDefaultAndConfigurable() {
    ConsoleAiProperties defaults = new ConsoleAiProperties();
    Binder binder = new Binder(
        new MapConfigurationPropertySource(Map.of("batch.console.ai.failover-enabled", "true")));

    assertThat(defaults.isFailoverEnabled()).isFalse();
    assertThat(binder
            .bind("batch.console.ai", Bindable.of(ConsoleAiProperties.class))
            .get()
            .isFailoverEnabled())
        .isTrue();
  }

  @Test
  void misspelledProvider_shouldFailBinding() {
    assertThatThrownBy(() -> bind("opeani"))
        .hasMessageContaining("batch.console.ai.provider")
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void defaultDomainKeywords_shouldCoverEngineeringGovernanceQuestions() {
    ConsoleAiProperties properties = new ConsoleAiProperties();

    assertThat(properties.getDomainKeywords())
        .contains("readiness", "trivy", "changelog", "dependency", "maven", "docker", "门禁");
  }

  @Test
  void attachmentStorage_shouldUseDedicatedBucketByDefaultAndAllowBinding() {
    ConsoleAiProperties defaults = new ConsoleAiProperties();
    Binder binder = new Binder(new MapConfigurationPropertySource(
        Map.of("batch.console.ai.attachment.storage-bucket", "tenant-ai-attachments")));

    assertThat(defaults.getAttachment().getStorageBucket()).isEqualTo("batch-ai-attachments");
    assertThat(binder
            .bind("batch.console.ai", Bindable.of(ConsoleAiProperties.class))
            .get()
            .getAttachment()
            .getStorageBucket())
        .isEqualTo("tenant-ai-attachments");
  }

  private static ConsoleAiProperties bind(String provider) {
    Binder binder = new Binder(
        new MapConfigurationPropertySource(Map.of("batch.console.ai.provider", provider)));
    return binder
        .bind("batch.console.ai", Bindable.of(ConsoleAiProperties.class))
        .get();
  }
}
