package com.example.batch.console.domain.ops.service;

import com.example.batch.console.domain.ops.entity.WorkerRegistryEntity;
import com.example.batch.console.domain.ops.mapper.WorkerRegistryMapper;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.support.cache.ConsoleQueryCacheService;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConsoleMyWorkerQueryService {

  private final WorkerRegistryMapper mapper;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleQueryCacheService cacheService;

  public List<WorkerRegistryEntity> listSelfHosted(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "workers:" + ConsoleQueryCacheService.keySegment(resolved) + ":self-hosted",
        ConsoleQueryCacheService.DIAGNOSTIC_TTL,
        new TypeReference<List<WorkerRegistryEntity>>() {},
        () -> mapper.selectSelfHostedByTenant(resolved));
  }

  public long countSelfHosted(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "workers:" + ConsoleQueryCacheService.keySegment(resolved) + ":self-hosted-count",
        ConsoleQueryCacheService.DIAGNOSTIC_TTL,
        Long.class,
        () -> mapper.countSelfHostedByTenant(resolved));
  }
}
