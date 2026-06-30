package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.FileChannelType;
import io.github.pinpols.batch.common.utils.Texts;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 官方分发渠道类型白名单。新增 adapter 类型必须先在这里显式登记。 */
final class DispatchChannelTypePolicy {

  private static final Set<String> OFFICIAL_TYPES = DictEnum.codes(FileChannelType.class);
  private static final String READBACK_NONE = "not implemented by current platform adapters";
  private static final Map<String, DispatchChannelSafetyProfile> SAFETY_PROFILES =
      buildSafetyProfiles();

  private DispatchChannelTypePolicy() {}

  static Set<String> allowedTypes() {
    return OFFICIAL_TYPES;
  }

  static Map<String, DispatchChannelSafetyProfile> safetyProfiles() {
    return SAFETY_PROFILES;
  }

  static Map<String, Object> safetyProfilesForAudit() {
    Map<String, Object> audit = new LinkedHashMap<>();
    SAFETY_PROFILES.forEach((type, profile) -> audit.put(type, profile.toAuditMap()));
    return Map.copyOf(audit);
  }

  static Optional<String> normalize(String channelType) {
    if (!Texts.hasText(channelType)) {
      return Optional.empty();
    }
    String normalized = channelType.trim().toUpperCase(Locale.ROOT);
    return OFFICIAL_TYPES.contains(normalized) ? Optional.of(normalized) : Optional.empty();
  }

  private static Map<String, DispatchChannelSafetyProfile> buildSafetyProfiles() {
    Map<String, DispatchChannelSafetyProfile> profiles = new LinkedHashMap<>();
    profiles.put(
        "API",
        profile(
            "API",
            Set.of(
                DispatchChannelSafetyAttribute.OFFICIAL_TYPE_ALLOWLIST,
                DispatchChannelSafetyAttribute.TIMEOUT_BOUND,
                DispatchChannelSafetyAttribute.SSRF_DNS_GUARD),
            "no built-in credential header; endpoint policy is external",
            READBACK_NONE,
            Set.of("no dispatch destination readback verification")));
    profiles.put(
        "API_PUSH",
        profile(
            "API_PUSH",
            Set.of(
                DispatchChannelSafetyAttribute.OFFICIAL_TYPE_ALLOWLIST,
                DispatchChannelSafetyAttribute.TIMEOUT_BOUND,
                DispatchChannelSafetyAttribute.SSRF_DNS_GUARD,
                DispatchChannelSafetyAttribute.CREDENTIAL_FROM_CHANNEL_CONFIG),
            "api_push_api_key and authorization are read from channel_config",
            READBACK_NONE,
            Set.of("no dispatch destination readback verification")));
    profiles.put(
        "LOCAL",
        profile(
            "LOCAL",
            Set.of(
                DispatchChannelSafetyAttribute.OFFICIAL_TYPE_ALLOWLIST,
                DispatchChannelSafetyAttribute.PATH_SANITIZED,
                DispatchChannelSafetyAttribute.FILESYSTEM_SANDBOX,
                DispatchChannelSafetyAttribute.SIDECAR_MANIFEST),
            "no remote credential",
            READBACK_NONE,
            Set.of("sandbox root is optional unless batch.dispatch.local-sandbox-root is set")));
    profiles.put(
        "NAS",
        profile(
            "NAS",
            Set.of(
                DispatchChannelSafetyAttribute.OFFICIAL_TYPE_ALLOWLIST,
                DispatchChannelSafetyAttribute.TIMEOUT_BOUND,
                DispatchChannelSafetyAttribute.PATH_SANITIZED,
                DispatchChannelSafetyAttribute.FILESYSTEM_SANDBOX,
                DispatchChannelSafetyAttribute.SIDECAR_MANIFEST),
            "filesystem permissions are external to the worker",
            READBACK_NONE,
            Set.of("sandbox root is optional unless batch.dispatch.nas-sandbox-root is set")));
    profiles.put(
        "OSS",
        profile(
            "OSS",
            Set.of(
                DispatchChannelSafetyAttribute.OFFICIAL_TYPE_ALLOWLIST,
                DispatchChannelSafetyAttribute.TIMEOUT_BOUND,
                DispatchChannelSafetyAttribute.PATH_SANITIZED,
                DispatchChannelSafetyAttribute.PAYLOAD_SIZE_BOUND,
                DispatchChannelSafetyAttribute.SIDECAR_MANIFEST,
                DispatchChannelSafetyAttribute.CREDENTIAL_FROM_CHANNEL_CONFIG),
            "uses central BatchObjectStore credentials and bucket policy",
            READBACK_NONE,
            Set.of("current dispatch path buffers payload before object-store put")));
    profiles.put(
        "SFTP",
        profile(
            "SFTP",
            Set.of(
                DispatchChannelSafetyAttribute.OFFICIAL_TYPE_ALLOWLIST,
                DispatchChannelSafetyAttribute.TIMEOUT_BOUND,
                DispatchChannelSafetyAttribute.SSRF_DNS_GUARD,
                DispatchChannelSafetyAttribute.PATH_SANITIZED,
                DispatchChannelSafetyAttribute.HOST_KEY_CHECK,
                DispatchChannelSafetyAttribute.SIDECAR_MANIFEST,
                DispatchChannelSafetyAttribute.CREDENTIAL_FROM_CHANNEL_CONFIG),
            "username/password and known_hosts are read from channel_config",
            READBACK_NONE,
            Set.of("StrictHostKeyChecking can be disabled outside prod profile")));
    profiles.put(
        "EMAIL",
        profile(
            "EMAIL",
            Set.of(
                DispatchChannelSafetyAttribute.OFFICIAL_TYPE_ALLOWLIST,
                DispatchChannelSafetyAttribute.TIMEOUT_BOUND,
                DispatchChannelSafetyAttribute.PAYLOAD_SIZE_BOUND,
                DispatchChannelSafetyAttribute.TLS_IDENTITY_CHECK,
                DispatchChannelSafetyAttribute.HEADER_INJECTION_GUARD,
                DispatchChannelSafetyAttribute.CREDENTIAL_FROM_CHANNEL_CONFIG),
            "smtp_username/smtp_password are read from channel_config",
            READBACK_NONE,
            Set.of("no dispatch destination readback verification")));
    if (!profiles.keySet().equals(OFFICIAL_TYPES)) {
      throw new IllegalStateException("dispatch safety profiles must cover every official type");
    }
    return Map.copyOf(profiles);
  }

  private static DispatchChannelSafetyProfile profile(
      String channelType,
      Set<DispatchChannelSafetyAttribute> attributes,
      String credentialHandling,
      String readbackSupport,
      Set<String> knownGaps) {
    return new DispatchChannelSafetyProfile(
        channelType, attributes, credentialHandling, readbackSupport, knownGaps);
  }
}
