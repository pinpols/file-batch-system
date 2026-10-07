package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.FileChannelType;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("分发渠道类型策略:允许类型的闭集、归一化、安全画像覆盖度与各类渠道的安全属性")
class DispatchChannelTypePolicyTest {

  @Test
  @DisplayName("允许的渠道类型是显式闭集,恰好覆盖全部官方类型")
  void shouldExposeClosedSetOfAllowedTypes_whenQueried() {
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
  @DisplayName("输入带空格且大小写不一时,归一化得到规范渠道类型")
  void shouldReturnCanonicalType_whenInputHasSpacesAndLowercase() {
    assertThat(DispatchChannelTypePolicy.normalize(" api_push ")).contains("API_PUSH");
  }

  @Test
  @DisplayName("未知渠道类型归一化返回空,不会被当成官方类型放行")
  void shouldReturnEmpty_whenTypeUnknown() {
    assertThat(DispatchChannelTypePolicy.normalize("WEBHOOK_RAW")).isEmpty();
  }

  @Test
  @DisplayName("安全画像的键集合与官方渠道类型集合完全一致,不留未覆盖类型")
  void shouldCoverEveryOfficialType_whenListingSafetyProfiles() {
    assertThat(DispatchChannelTypePolicy.safetyProfiles().keySet())
        .containsExactlyInAnyOrderElementsOf(DispatchChannelTypePolicy.allowedTypes());
  }

  @Test
  @DisplayName("官方类型缺少一份安全画像时,启动不变量校验立即失败并说明覆盖要求")
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
  @DisplayName("安全画像与官方类型完全对齐时,启动不变量校验通过且不抛异常")
  void requireFullCoverage_passes_whenProfilesExactlyMatchOfficialTypes() {
    Set<String> officialTypes = new HashSet<>(DispatchChannelTypePolicy.allowedTypes());

    assertThatCode(() -> DispatchChannelTypePolicy.requireFullCoverage(
            new HashSet<>(officialTypes), officialTypes))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("接口类渠道画像声明超时受控与域名防护,推送类渠道额外声明凭据取自渠道配置")
  void shouldDeclareTimeoutAndDnsGuard_whenHttpProfilesInspected() {
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
  @DisplayName("文件系统类画像把已有能力与已知缺口分开标注,本地沙箱根未配置时计入已知缺口")
  void shouldSeparateCapabilitiesFromKnownGaps_whenFilesystemProfilesInspected() {
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
  @DisplayName("邮件渠道画像声明超时与报文大小受控、传输身份校验与请求头注入防护,且不再列套接字超时缺口")
  void shouldDeclareSocketTimeout_whenEmailProfileInspected() {
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
