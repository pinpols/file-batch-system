package com.example.batch.console.infrastructure.ops;

import com.example.batch.console.application.ops.ConsoleOrchestratorProxyService;
import com.example.batch.console.application.ops.ConsoleOutboxOpsApplicationService;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.mapper.OutboxEventMapper;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.web.response.ops.ConsoleOutboxCleanupResponse;
import com.example.batch.console.web.response.ops.ConsoleOutboxRepublishResponse;
import com.example.batch.console.web.response.ops.ConsoleOutboxStatsResponse;
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
  private final OutboxEventMapper outboxEventMapper;
  private final ConsoleRealtimeDomainEventPublisher domainEventPublisher;
  private final ConsoleOrchestratorProxyService orchestratorProxy;

  @Override
  public ConsoleOutboxStatsResponse stats(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return new ConsoleOutboxStatsResponse(resolved, outboxEventMapper.statsByStatus(resolved));
  }

  @Override
  public ConsoleOutboxCleanupResponse cleanup(String tenantId, int retainDays) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Integer> result = orchestratorProxy.outboxCleanup(resolved, retainDays);
    int pub = result.getOrDefault("published", 0);
    int giveUp = result.getOrDefault("giveUp", 0);
    domainEventPublisher.publishChanged(resolved, "outbox-deliveries", "outbox-cleanup");
    return new ConsoleOutboxCleanupResponse(resolved, retainDays, pub, giveUp);
  }

  @Override
  public ConsoleOutboxRepublishResponse republish(String tenantId, List<Long> ids) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Integer> result = orchestratorProxy.outboxRepublish(resolved, ids);
    int reset = result.getOrDefault("reset", 0);
    domainEventPublisher.publishChanged(resolved, "outbox-retries", "outbox-republish");
    domainEventPublisher.publishChanged(resolved, "outbox-deliveries", "outbox-republish");
    return new ConsoleOutboxRepublishResponse(resolved, ids.size(), reset);
  }
}
