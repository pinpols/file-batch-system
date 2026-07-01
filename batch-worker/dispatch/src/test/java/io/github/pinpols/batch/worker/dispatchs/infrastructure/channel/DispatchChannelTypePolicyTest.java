package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
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
  void requireFullCoverage_throws_whenAProfileIsMissing() {
    // arrange:官方类型少了 EMAIL 的一份 profile 覆盖
    Set<String> officialTypes = new HashSet<>(DispatchChannelTypePolicy.allowedTypes());
    Set<String> profileKeys = new HashSet<>(officialTypes);
    profileKeys.remove("EMAIL");

    // act + assert:启动不变量必须 fail-fast
    assertThatThrownBy(
            () -> DispatchChannelTypePolicy.requireFullCoverage(profileKeys, officialTypes))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must cover every official type");
  }

  @Test
  void requireFullCoverage_passes_whenProfilesExactlyMatchOfficialTypes() {
    Set<String> officialTypes = new HashSet<>(DispatchChannelTypePolicy.allowedTypes());

    assertThatCode(
            () ->
                DispatchChannelTypePolicy.requireFullCoverage(
                    new HashSet<>(officialTypes), officialTypes))
        .doesNotThrowAnyException();
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
        .contains("sandbox root is optional unless batch.dispatch.local-sandbox-root is set");
  }

  @Test
  void emailProfileDeclaresSocketTimeout() {
    assertThat(DispatchChannelTypePolicy.safetyProfiles().get("EMAIL").attributes())
        .contains(
            DispatchChannelSafetyAttribute.TIMEOUT_BOUND,
            DispatchChannelSafetyAttribute.PAYLOAD_SIZE_BOUND,
            DispatchChannelSafetyAttribute.TLS_IDENTITY_CHECK,
            DispatchChannelSafetyAttribute.HEADER_INJECTION_GUARD);
    assertThat(DispatchChannelTypePolicy.safetyProfiles().get("EMAIL").knownGaps())
        .doesNotContain("SMTP dispatch has no explicit socket timeout properties");
  }
}
