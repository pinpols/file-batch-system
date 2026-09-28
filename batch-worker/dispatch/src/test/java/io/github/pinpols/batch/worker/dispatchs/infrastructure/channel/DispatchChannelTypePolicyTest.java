package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.FileChannelType;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DispatchChannelTypePolicyTest {

  @Test
  void allowedTypesAreExplicitAndClosed() {
    assertThat(DispatchChannelTypePolicy.allowedTypes())
        .containsExactlyInAnyOrder(
            FileChannelType.API.code(),
            FileChannelType.API_PUSH.code(),
            FileChannelType.LOCAL.code(),
            FileChannelType.NAS.code(),
            FileChannelType.OSS.code(),
            FileChannelType.SFTP.code(),
            FileChannelType.EMAIL.code());
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
    profileKeys.remove(FileChannelType.EMAIL.code());

    // act + assert:启动不变量必须 fail-fast
    assertThatThrownBy(
            () -> DispatchChannelTypePolicy.requireFullCoverage(profileKeys, officialTypes))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must cover every official type");
  }

  @Test
  void requireFullCoverage_passes_whenProfilesExactlyMatchOfficialTypes() {
    Set<String> officialTypes = new HashSet<>(DispatchChannelTypePolicy.allowedTypes());

    assertThatCode(() -> DispatchChannelTypePolicy.requireFullCoverage(
            new HashSet<>(officialTypes), officialTypes))
        .doesNotThrowAnyException();
  }

  @Test
  void httpProfilesDeclareTimeoutAndDnsGuard() {
    assertThat(DispatchChannelTypePolicy.safetyProfiles()
            .get(FileChannelType.API.code())
            .attributes())
        .contains(
            DispatchChannelSafetyAttribute.TIMEOUT_BOUND,
            DispatchChannelSafetyAttribute.SSRF_DNS_GUARD);
    assertThat(DispatchChannelTypePolicy.safetyProfiles()
            .get(FileChannelType.API_PUSH.code())
            .attributes())
        .contains(
            DispatchChannelSafetyAttribute.TIMEOUT_BOUND,
            DispatchChannelSafetyAttribute.SSRF_DNS_GUARD,
            DispatchChannelSafetyAttribute.CREDENTIAL_FROM_CHANNEL_CONFIG);
  }

  @Test
  void filesystemProfilesSeparateCapabilitiesFromKnownGaps() {
    assertThat(DispatchChannelTypePolicy.safetyProfiles()
            .get(FileChannelType.NAS.code())
            .attributes())
        .contains(
            DispatchChannelSafetyAttribute.PATH_SANITIZED,
            DispatchChannelSafetyAttribute.FILESYSTEM_SANDBOX,
            DispatchChannelSafetyAttribute.SIDECAR_MANIFEST);
    assertThat(DispatchChannelTypePolicy.safetyProfiles()
            .get(FileChannelType.LOCAL.code())
            .knownGaps())
        .contains(
            "sandbox root is optional unless batch.worker.dispatch.runtime.local-sandbox-root is"
                + " set");
  }

  @Test
  void emailProfileDeclaresSocketTimeout() {
    assertThat(DispatchChannelTypePolicy.safetyProfiles()
            .get(FileChannelType.EMAIL.code())
            .attributes())
        .contains(
            DispatchChannelSafetyAttribute.TIMEOUT_BOUND,
            DispatchChannelSafetyAttribute.PAYLOAD_SIZE_BOUND,
            DispatchChannelSafetyAttribute.TLS_IDENTITY_CHECK,
            DispatchChannelSafetyAttribute.HEADER_INJECTION_GUARD);
    assertThat(DispatchChannelTypePolicy.safetyProfiles()
            .get(FileChannelType.EMAIL.code())
            .knownGaps())
        .doesNotContain("SMTP dispatch has no explicit socket timeout properties");
  }
}
