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

  @Test
  void safetyProfilesCoverEveryOfficialType() {
    assertThat(DispatchChannelTypePolicy.safetyProfiles().keySet())
        .containsExactlyInAnyOrderElementsOf(DispatchChannelTypePolicy.allowedTypes());
  }

  @Test
  void httpProfilesDeclareTimeoutAndDnsGuard() {
    assertThat(DispatchChannelTypePolicy.safetyProfiles().get("API").attributes())
        .contains(
            DispatchChannelSafetyAttribute.TIMEOUT_BOUND,
            DispatchChannelSafetyAttribute.SSRF_DNS_GUARD);
    assertThat(DispatchChannelTypePolicy.safetyProfiles().get("API_PUSH").attributes())
        .contains(
            DispatchChannelSafetyAttribute.TIMEOUT_BOUND,
            DispatchChannelSafetyAttribute.SSRF_DNS_GUARD,
            DispatchChannelSafetyAttribute.CREDENTIAL_FROM_CHANNEL_CONFIG);
  }

  @Test
  void filesystemProfilesSeparateCapabilitiesFromKnownGaps() {
    assertThat(DispatchChannelTypePolicy.safetyProfiles().get("NAS").attributes())
        .contains(
            DispatchChannelSafetyAttribute.PATH_SANITIZED,
            DispatchChannelSafetyAttribute.FILESYSTEM_SANDBOX,
            DispatchChannelSafetyAttribute.SIDECAR_MANIFEST);
    assertThat(DispatchChannelTypePolicy.safetyProfiles().get("LOCAL").knownGaps())
        .contains("target_endpoint is not sandbox-bound");
  }

  @Test
  void emailProfileDoesNotPretendSocketTimeoutExists() {
    assertThat(DispatchChannelTypePolicy.safetyProfiles().get("EMAIL").attributes())
        .doesNotContain(DispatchChannelSafetyAttribute.TIMEOUT_BOUND)
        .contains(
            DispatchChannelSafetyAttribute.PAYLOAD_SIZE_BOUND,
            DispatchChannelSafetyAttribute.TLS_IDENTITY_CHECK,
            DispatchChannelSafetyAttribute.HEADER_INJECTION_GUARD);
    assertThat(DispatchChannelTypePolicy.safetyProfiles().get("EMAIL").knownGaps())
        .contains("SMTP dispatch has no explicit socket timeout properties");
  }
}
