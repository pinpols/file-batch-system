package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.utils.Texts;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 官方分发渠道类型白名单。新增 adapter 类型必须先在这里显式登记。 */
final class DispatchChannelTypePolicy {

  private static final Set<String> OFFICIAL_TYPES =
      Set.of("API", "API_PUSH", "LOCAL", "NAS", "OSS", "SFTP", "EMAIL");

  private DispatchChannelTypePolicy() {}

  static Set<String> allowedTypes() {
    return OFFICIAL_TYPES;
  }

  static Optional<String> normalize(String channelType) {
    if (!Texts.hasText(channelType)) {
      return Optional.empty();
    }
    String normalized = channelType.trim().toUpperCase(Locale.ROOT);
    return OFFICIAL_TYPES.contains(normalized) ? Optional.of(normalized) : Optional.empty();
  }
}
