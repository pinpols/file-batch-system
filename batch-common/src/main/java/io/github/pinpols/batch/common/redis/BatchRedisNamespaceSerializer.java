package io.github.pinpols.batch.common.redis;

import java.nio.charset.StandardCharsets;

import org.springframework.data.redis.serializer.RedisSerializer;

/** 为 Redis 物理键添加前缀，经模板回读时移除该前缀。 */
public final class BatchRedisNamespaceSerializer implements RedisSerializer<String> {

    private final BatchRedisNamespace namespace;

    public BatchRedisNamespaceSerializer(BatchRedisNamespace namespace) {
        this.namespace = namespace;
    }

    @Override
    public byte[] serialize(String key) {
        if (key == null) {
            return null;
        }
        String physicalKey = namespace.key(key);
        return physicalKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String deserialize(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        String decodedPhysicalKey = new String(bytes, StandardCharsets.UTF_8);
        return namespace.logicalKey(decodedPhysicalKey);
    }
}
