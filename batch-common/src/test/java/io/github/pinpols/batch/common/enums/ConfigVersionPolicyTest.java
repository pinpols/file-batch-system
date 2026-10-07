package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConfigVersionPolicy 配置版本策略: 规范编码与历史编码的兼容解析")
class ConfigVersionPolicyTest {

  @Test
  @DisplayName("规范编码 / 带空白的编码 / 历史编码 命中同一策略,未知编码返回空")
  void shouldResolveCanonicalAndLegacyCodes() {
    assertThat(ConfigVersionPolicy.fromCodeOrNull("USE_LATEST_CONFIG"))
        .isEqualTo(ConfigVersionPolicy.USE_LATEST_CONFIG);
    assertThat(ConfigVersionPolicy.fromCodeOrNull(" use_current_config "))
        .isEqualTo(ConfigVersionPolicy.USE_LATEST_CONFIG);
    assertThat(ConfigVersionPolicy.fromCodeOrNull("USE_SPECIFIC_VERSION"))
        .isEqualTo(ConfigVersionPolicy.USE_SPECIFIED_VERSION);
    assertThat(ConfigVersionPolicy.fromCodeOrNull("UNKNOWN")).isNull();
  }
}
