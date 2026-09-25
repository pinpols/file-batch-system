package io.github.pinpols.batch.console.application.observability;

import io.github.pinpols.batch.console.domain.observability.entity.SystemParameterEntity;
import io.github.pinpols.batch.console.domain.observability.mapper.ConsoleSystemParameterMapper;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleSystemParameterService {

  private final ConsoleSystemParameterMapper repository;
  private final TenantIdResolver tenantGuard;
  private final SystemParameterCacheStore cacheStore;

  private static final String CACHE_PREFIX = "sys-param:";
  private static final Duration CACHE_TTL = Duration.ofMinutes(30);

  public List<SystemParameterEntity> list(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return repository.findAllByTenant(resolved);
  }

  public Optional<String> getValue(String tenantId, String paramKey) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String cacheKey = cacheKey(resolved, paramKey);
    String cached = cacheStore.get(cacheKey);
    if (cached != null) {
      return Optional.of(cached);
    }
    Optional<SystemParameterEntity> entity = repository.findByTenantAndKey(resolved, paramKey);
    entity.ifPresent(e -> cacheStore.put(cacheKey, e.getParamValue(), CACHE_TTL));
    return entity.map(SystemParameterEntity::getParamValue);
  }

  @Transactional
  public void upsert(
      String tenantId, String paramKey, String paramValue, String description, String operator) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    repository.upsert(resolved, paramKey, paramValue, description, operator);
    cacheStore.evict(cacheKey(resolved, paramKey));
  }

  @Transactional
  public void delete(String tenantId, String paramKey) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    repository.deleteByTenantAndKey(resolved, paramKey);
    cacheStore.evict(cacheKey(resolved, paramKey));
  }

  private String cacheKey(String tenantId, String paramKey) {
    return CACHE_PREFIX + tenantId + ":" + paramKey;
  }
}
