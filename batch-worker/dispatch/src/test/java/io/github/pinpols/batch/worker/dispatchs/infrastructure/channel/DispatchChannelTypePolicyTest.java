package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DispatchChannelTypePolicyTest {

  @Test
  void allowedTypesAreExplicitAndClosed() {
    assertThat(DispatchChannelTypePolicy.allowedTypes())
        .containsExactlyInAnyOrder("API", "API_PUSH", "LOCAL", "NAS", "OSS", "SFTP", "EMAIL");
  }

  @Test
  void normalizeReturnsCanonicalType() {
    assertThat(DispatchChannelTypePolicy.normalize(" api_push ")).contains("API_PUSH");
  }

  @Test
  void normalizeRejectsUnknownType() {
    assertThat(DispatchChannelTypePolicy.normalize("WEBHOOK_RAW")).isEmpty();
  }
}
