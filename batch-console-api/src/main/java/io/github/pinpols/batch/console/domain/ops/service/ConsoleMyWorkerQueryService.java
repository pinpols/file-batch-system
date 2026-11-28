package io.github.pinpols.batch.console.domain.ops.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.console.domain.ops.mapper.WorkerRegistryMapper;
import io.github.pinpols.batch.console.domain.ops.view.WorkerRegistryView;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;

@Service
@RequiredArgsConstructor
public class ConsoleMyWorkerQueryService {

    private final WorkerRegistryMapper mapper;
    private final TenantIdResolver tenantGuard;
    private final ConsoleQueryCacheService cacheService;

    public List<WorkerRegistryView> listSelfHosted(String tenantId) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        return cacheService.getOrLoad(
                "workers:" + ConsoleQueryCacheService.keySegment(resolved) + ":self-hosted",
                ConsoleQueryCacheService.DIAGNOSTIC_TTL,
                new TypeReference<List<WorkerRegistryView>>() {},
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
