package io.github.pinpols.batch.common.redis;

import java.util.Objects;
import java.util.regex.Pattern;

import io.github.pinpols.batch.common.utils.EmptyValues;

/** 将部署命名空间应用于 Redis 键、扫描模式和发布订阅通道。 */
public final class BatchRedisNamespace {

    private static final Pattern VALID_NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");

    private final String prefix;

    public BatchRedisNamespace(String namespace) {
        String normalized = namespace == null ? "" : namespace.trim();
        if (!EmptyValues.isEmpty(Objects.requireNonNull(normalized))
                && !VALID_NAMESPACE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("batch.kafka.namespace must match [a-z0-9][a-z0-9_-]{0,47}");
        }
        this.prefix = EmptyValues.isEmpty(Objects.requireNonNull(normalized)) ? "" : normalized + ":";
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
        return !EmptyValues.isEmpty(Objects.requireNonNull(prefix));
    }

    String logicalKey(String key) {
        return !EmptyValues.isEmpty(Objects.requireNonNull(prefix)) && key.startsWith(prefix)
                ? key.substring(prefix.length())
                : key;
    }

    String prefix() {
        return prefix;
    }
}
