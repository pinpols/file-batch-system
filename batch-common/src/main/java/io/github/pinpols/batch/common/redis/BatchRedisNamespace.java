package io.github.pinpols.batch.common.redis;

import java.util.regex.Pattern;

/** Applies the deployment namespace to Redis keys, scan patterns, and Pub/Sub channels. */
public final class BatchRedisNamespace {

  private static final Pattern VALID_NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");

  private final String prefix;

  public BatchRedisNamespace(String namespace) {
    String normalized = namespace == null ? "" : namespace.trim();
    if (!normalized.isEmpty() && !VALID_NAMESPACE.matcher(normalized).matches()) {
      throw new IllegalArgumentException("batch.kafka.namespace must match [a-z0-9][a-z0-9_-]{0,47}");
    }
    this.prefix = normalized.isEmpty() ? "" : normalized + ":";
  }

  public String key(String key) {
    return key == null ? null : prefix + key;
  }

  public String scanPattern(String pattern) {
    return prefix + pattern;
  }

  public String channel(String channel) {
    return channel == null ? null : prefix + channel;
  }

  public boolean isNamespaced() {
    return !prefix.isEmpty();
  }

  String logicalKey(String key) {
    return !prefix.isEmpty() && key.startsWith(prefix) ? key.substring(prefix.length()) : key;
  }

  String prefix() {
    return prefix;
  }
}
