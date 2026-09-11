package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfigVersionPolicyTest {

  @Test
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
