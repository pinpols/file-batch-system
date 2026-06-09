package com.example.batch.console.domain.ops.infrastructure;

import com.example.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.domain.ops.application.ConsoleOrchestratorProxyService;
import com.example.batch.console.domain.ops.application.ConsoleOutboxOpsApplicationService;
import com.example.batch.console.domain.ops.mapper.ConsoleOutboxEventReadMapper;
import com.example.batch.console.domain.ops.web.response.ConsoleOutboxCleanupResponse;
import com.example.batch.console.domain.ops.web.response.ConsoleOutboxRepublishResponse;
import com.example.batch.console.domain.ops.web.response.ConsoleOutboxStatsResponse;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.support.cache.ConsoleQueryCacheService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Console outbox 运维应用层：stats 仍在本地直接 SELECT，cleanup / republish 转发到 orchestrator。
 *
 * <p>转发原因：CLAUDE.md「Orchestrator 是唯一状态主机」硬约束 — outbox_event 是分发主链的核心环节， console 不能直接
 * UPDATE/DELETE。改由 orchestrator 在自己 @Transactional 边界里执行。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleOutboxOpsApplicationService
    implements ConsoleOutboxOpsApplicationService {

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleOutboxEventReadMapper consoleOutboxEventReadMapper;
  private final ConsoleRealtimeDomainEventPublisher domainEventPublisher;
  private final ConsoleOrchestratorProxyService orchestratorProxy;
  private final ConsoleQueryCacheService cacheService;

  @Override
  public ConsoleOutboxStatsResponse stats(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "dashboard:" + ConsoleQueryCacheService.keySegment(resolved) + ":outbox-stats",
        ConsoleQueryCacheService.DASHBOARD_TTL,
        ConsoleOutboxStatsResponse.class,
        () ->
            new ConsoleOutboxStatsResponse(
                resolved, consoleOutboxEventReadMapper.statsByStatus(resolved)));
  }

  @Override
  public ConsoleOutboxCleanupResponse cleanup(String tenantId, int retainDays) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Integer> result = orchestratorProxy.outboxCleanup(resolved, retainDays);
    int pub = result.getOrDefault("published", 0);
    int giveUp = result.getOrDefault("giveUp", 0);
    domainEventPublisher.publishChanged(resolved, "outbox-deliveries", "outbox-cleanup");
    cacheService.evictDashboard(resolved);
    return new ConsoleOutboxCleanupResponse(resolved, retainDays, pub, giveUp);
  }

  @Override
  public ConsoleOutboxRepublishResponse republish(String tenantId, List<Long> ids) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Integer> result = orchestratorProxy.outboxRepublish(resolved, ids);
    int reset = result.getOrDefault("reset", 0);
    domainEventPublisher.publishChanged(resolved, "outbox-retries", "outbox-republish");
    domainEventPublisher.publishChanged(resolved, "outbox-deliveries", "outbox-republish");
    cacheService.evictDashboard(resolved);
    return new ConsoleOutboxRepublishResponse(resolved, ids.size(), reset);
  }
}
