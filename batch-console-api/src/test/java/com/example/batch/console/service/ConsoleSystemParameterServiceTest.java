package com.example.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.console.domain.entity.SystemParameterEntity;
import com.example.batch.console.repository.ConsoleSystemParameterRepository;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings("unchecked")
class ConsoleSystemParameterServiceTest {

    private ConsoleSystemParameterRepository repository;
    private ConsoleTenantGuard tenantGuard;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ConsoleSystemParameterService service;

    @BeforeEach
    void setUp() {
        repository = mock(ConsoleSystemParameterRepository.class);
        tenantGuard = mock(ConsoleTenantGuard.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new ConsoleSystemParameterService(repository, tenantGuard, redisTemplate);
        when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
    }

    @Test
    void shouldListParameters() {
        SystemParameterEntity entity = new SystemParameterEntity();
        entity.setParamKey("batch.size");
        when(repository.findAllByTenant("t1")).thenReturn(List.of(entity));

        List<SystemParameterEntity> result = service.list("t1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getParamKey()).isEqualTo("batch.size");
    }

    @Test
    void shouldReturnCachedValueFromRedis() {
        when(valueOperations.get("sys-param:t1:batch.size")).thenReturn("500");

        Optional<String> result = service.getValue("t1", "batch.size");

        assertThat(result).contains("500");
    }

    @Test
    void shouldFallbackToDbWhenCacheMiss() {
        when(valueOperations.get("sys-param:t1:batch.size")).thenReturn(null);
        SystemParameterEntity entity = new SystemParameterEntity();
        entity.setParamValue("200");
        when(repository.findByTenantAndKey("t1", "batch.size")).thenReturn(Optional.of(entity));

        Optional<String> result = service.getValue("t1", "batch.size");

        assertThat(result).contains("200");
        verify(valueOperations).set("sys-param:t1:batch.size", "200", Duration.ofMinutes(30));
    }

    @Test
    void shouldReturnEmptyWhenCacheMissAndDbMiss() {
        when(valueOperations.get("sys-param:t1:missing")).thenReturn(null);
        when(repository.findByTenantAndKey("t1", "missing")).thenReturn(Optional.empty());

        Optional<String> result = service.getValue("t1", "missing");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldUpsertAndClearCache() {
        service.upsert("t1", "batch.size", "300", "batch size config", "admin");

        verify(repository).upsert("t1", "batch.size", "300", "batch size config", "admin");
        verify(redisTemplate).delete("sys-param:t1:batch.size");
    }

    @Test
    void shouldDeleteAndClearCache() {
        service.delete("t1", "batch.size");

        verify(repository).deleteByTenantAndKey("t1", "batch.size");
        verify(redisTemplate).delete("sys-param:t1:batch.size");
    }
}
