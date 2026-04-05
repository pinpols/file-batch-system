package com.example.batch.console.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.console.config.ConsoleSecurityProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ConsoleSessionRegistryTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ConsoleSessionRegistry registry;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ConsoleSecurityProperties properties = new ConsoleSecurityProperties();
        properties.setSingleSessionEnabled(true);
        properties.setSessionStateTtl(Duration.ofDays(30));
        registry = new ConsoleSessionRegistry(redisTemplate, properties);
    }

    @Test
    void shouldIncrementAndPersistSessionVersion() {
        when(valueOperations.increment("batch:console:auth:session:tenant-a:alice")).thenReturn(3L);

        long version = registry.nextSessionVersion("alice", "tenant-a");

        assertThat(version).isEqualTo(3L);
        verify(redisTemplate).expire("batch:console:auth:session:tenant-a:alice", Duration.ofDays(30));
    }

    @Test
    void shouldMatchCurrentSessionVersion() {
        when(valueOperations.get("batch:console:auth:session:tenant-a:alice")).thenReturn("5");

        assertThat(registry.isCurrentSession("alice", "tenant-a", 5L)).isTrue();
        assertThat(registry.isCurrentSession("alice", "tenant-a", 4L)).isFalse();
    }
}
