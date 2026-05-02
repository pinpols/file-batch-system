package com.example.batch.console.service;

import com.example.batch.console.domain.entity.SystemParameterEntity;
import com.example.batch.console.mapper.ConsoleSystemParameterMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleSystemParameterService {

  private final ConsoleSystemParameterMapper repository;
  private final ConsoleTenantGuard tenantGuard;
  private final StringRedisTemplate redisTemplate;

  private static final String CACHE_PREFIX = "sys-param:";
  private static final Duration CACHE_TTL = Duration.ofMinutes(30);

  public List<SystemParameterEntity> list(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return repository.findAllByTenant(resolved);
  }

  public Optional<String> getValue(String tenantId, String paramKey) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String cacheKey = cacheKey(resolved, paramKey);
    String cached = redisTemplate.opsForValue().get(cacheKey);
    if (cached != null) {
      return Optional.of(cached);
    }
    Optional<SystemParameterEntity> entity = repository.findByTenantAndKey(resolved, paramKey);
    entity.ifPresent(e -> redisTemplate.opsForValue().set(cacheKey, e.getParamValue(), CACHE_TTL));
    return entity.map(SystemParameterEntity::getParamValue);
  }

  @Transactional
  public void upsert(
      String tenantId, String paramKey, String paramValue, String description, String operator) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    repository.upsert(resolved, paramKey, paramValue, description, operator);
    redisTemplate.delete(cacheKey(resolved, paramKey));
  }

  @Transactional
  public void delete(String tenantId, String paramKey) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    repository.deleteByTenantAndKey(resolved, paramKey);
    redisTemplate.delete(cacheKey(resolved, paramKey));
  }

  private String cacheKey(String tenantId, String paramKey) {
    return CACHE_PREFIX + tenantId + ":" + paramKey;
  }
}
