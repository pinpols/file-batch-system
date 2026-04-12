package com.example.batch.console.infrastructure;

import com.example.batch.console.application.ConsoleOutboxOpsApplicationService;
import com.example.batch.console.mapper.OutboxEventMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.response.ConsoleOutboxCleanupResponse;
import com.example.batch.console.web.response.ConsoleOutboxRepublishResponse;
import com.example.batch.console.web.response.ConsoleOutboxStatsResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DefaultConsoleOutboxOpsApplicationService
    implements ConsoleOutboxOpsApplicationService {

  private final ConsoleTenantGuard tenantGuard;
  private final OutboxEventMapper outboxEventMapper;

  @Override
  public ConsoleOutboxStatsResponse stats(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return new ConsoleOutboxStatsResponse(resolved, outboxEventMapper.statsByStatus(resolved));
  }

  @Override
  @Transactional
  public ConsoleOutboxCleanupResponse cleanup(String tenantId, int retainDays) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Instant cutoff = Instant.now().minus(retainDays, ChronoUnit.DAYS);
    int pub = outboxEventMapper.deletePublishedBefore(resolved, cutoff);
    int giveUp = outboxEventMapper.deleteGiveUpBefore(resolved, cutoff);
    return new ConsoleOutboxCleanupResponse(resolved, retainDays, pub, giveUp);
  }

  @Override
  @Transactional
  public ConsoleOutboxRepublishResponse republish(String tenantId, List<Long> ids) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    int reset = outboxEventMapper.resetToNew(resolved, ids, List.of("FAILED", "GIVE_UP"));
    return new ConsoleOutboxRepublishResponse(resolved, ids.size(), reset);
  }
}
