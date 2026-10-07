package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

@DisplayName("控制台人工智能配置项: 供应商,故障转移,领域关键词与附件桶绑定")
class ConsoleAiPropertiesTest {

  @Test
  @DisplayName("供应商名大小写不敏感,同一枚举值均可绑定成功")
  void validProvider_shouldBindCaseInsensitively() {
    ConsoleAiProperties properties = bind("openai");

    assertThat(properties.getProvider()).isEqualTo(ConsoleAiProperties.Provider.OPENAI);
  }

  @Test
  @DisplayName("连字符形式的兼容供应商名,可绑定到对应枚举值")
  void openAiCompatibleProvider_shouldBindWithHyphenatedValue() {
    ConsoleAiProperties properties = bind("openai-compatible");

    assertThat(properties.getProvider()).isEqualTo(ConsoleAiProperties.Provider.OPENAI_COMPATIBLE);
  }

  @Test
  @DisplayName("跨供应商故障转移默认关闭,显式配置后按绑定值开启")
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
  @DisplayName("供应商名拼写错误时,绑定失败并指出配置项路径")
  void misspelledProvider_shouldFailBinding() {
    assertThatThrownBy(() -> bind("opeani"))
        .hasMessageContaining("batch.console.ai.provider")
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("默认领域关键词覆盖面,涵盖工程治理的常见提问方向")
  void defaultDomainKeywords_shouldCoverEngineeringGovernanceQuestions() {
    ConsoleAiProperties properties = new ConsoleAiProperties();

    assertThat(properties.getDomainKeywords())
        .contains("readiness", "trivy", "changelog", "dependency", "maven", "docker", "门禁");
  }

  @Test
  @DisplayName("附件存储默认使用独立桶,且允许通过外部配置覆盖")
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
