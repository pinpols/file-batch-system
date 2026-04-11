package com.example.batch.console.support;

import com.example.batch.console.config.ConsoleSecurityProperties;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;

/** 控制台单会话注册表：按租户+用户名记录当前有效的会话版本。 */
@Service
public class ConsoleSessionRegistry {

    private static final String KEY_PREFIX = "batch:console:auth:session:";

    private final StringRedisTemplate redisTemplate;
    private final ConsoleSecurityProperties securityProperties;

    public ConsoleSessionRegistry(
            StringRedisTemplate redisTemplate, ConsoleSecurityProperties securityProperties) {
        this.redisTemplate = redisTemplate;
        this.securityProperties = securityProperties;
    }

    public long nextSessionVersion(String username, String tenantId) {
        if (!securityProperties.isSingleSessionEnabled()) {
            return 0L;
        }
        String key = key(username, tenantId);
        Long version = redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, sessionStateTtl());
        return version == null ? 1L : version;
    }

    public long currentSessionVersion(String username, String tenantId) {
        if (!securityProperties.isSingleSessionEnabled()) {
            return 0L;
        }
        ValueOperations<String, String> ops = redisTemplate.opsForValue();
        String raw = ops.get(key(username, tenantId));
        if (!StringUtils.hasText(raw)) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public boolean isCurrentSession(String username, String tenantId, long sessionVersion) {
        if (!securityProperties.isSingleSessionEnabled()) {
            return true;
        }
        long current = currentSessionVersion(username, tenantId);
        return current > 0L && current == sessionVersion;
    }

    private Duration sessionStateTtl() {
        Duration ttl = securityProperties.getSessionStateTtl();
        return ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofDays(30) : ttl;
    }

    private String key(String username, String tenantId) {
        return KEY_PREFIX + normalize(tenantId) + ":" + normalize(username);
    }

    private String normalize(String value) {
        return value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
    }
}
