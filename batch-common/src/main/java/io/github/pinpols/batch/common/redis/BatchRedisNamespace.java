package io.github.pinpols.batch.common.redis;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Objects;
import java.util.regex.Pattern;

/** 将部署命名空间应用于 Redis 键、扫描模式和发布订阅通道。 */
public final class BatchRedisNamespace {

  private static final Pattern VALID_NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");

  private final String prefix;

  public BatchRedisNamespace(String namespace) {
    String normalized = EmptyChecks.isNull(namespace) ? "" : namespace.trim();
    if (!EmptyChecks.isEmpty(Objects.requireNonNull(normalized))
        && !VALID_NAMESPACE.matcher(normalized).matches()) {
      throw new IllegalArgumentException(
          "batch.kafka.namespace must match [a-z0-9][a-z0-9_-]{0,47}");
    }
    this.prefix = EmptyChecks.isEmpty(Objects.requireNonNull(normalized)) ? "" : normalized + ":";
  }

  public String key(String key) {
    return EmptyChecks.isNull(key) ? null : prefix + key;
  }

  public String scanPattern(String pattern) {
    return prefix + pattern;
  }

  public String channel(String channel) {
    return EmptyChecks.isNull(channel) ? null : prefix + channel;
  }

  public boolean isNamespaced() {
    return !EmptyChecks.isEmpty(Objects.requireNonNull(prefix));
  }

  String logicalKey(String key) {
    return !EmptyChecks.isEmpty(Objects.requireNonNull(prefix)) && key.startsWith(prefix)
        ? key.substring(prefix.length())
        : key;
  }

  String prefix() {
    return prefix;
  }
}
