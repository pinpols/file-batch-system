package com.example.batch.orchestrator.application.service.governance;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 运维应用服务：承接 console 通过 HTTP 转发过来的 outbox cleanup / republish 操作。
 *
 * <p>由于 CLAUDE.md 「Orchestrator 是唯一状态主机」硬约束，console-api 不能直接 UPDATE/DELETE outbox_event； 改由本服务在
 * orchestrator 内部 @Transactional 边界里执行，调用栈：
 *
 * <pre>
 * console-api ConsoleOutboxOpsApplicationService
 *   → ConsoleOrchestratorProxyService (HTTP)
 *   → orchestrator OutboxOpsController
 *   → 本服务（OutboxOpsApplicationService）
 *   → OutboxEventMapper
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class OutboxOpsApplicationService {

  private static final List<String> REPUBLISHABLE_FROM_STATUSES = List.of("FAILED", "GIVE_UP");

  private final OutboxEventMapper outboxEventMapper;

  /**
   * 删除指定租户中 PUBLISHED + GIVE_UP、且 updated_at 早于 retainDays 的事件。 用于 outbox 表瘦身（事件已终结，不影响活跃业务）。
   *
   * @return key=published / give_up 的删除条数
   */
  @Transactional
  public Map<String, Integer> cleanup(String tenantId, int retainDays) {
    Instant cutoff = BatchDateTimeSupport.utcNow().minus(retainDays, ChronoUnit.DAYS);
    int published = outboxEventMapper.deletePublishedBefore(tenantId, cutoff);
    int giveUp = outboxEventMapper.deleteGiveUpBefore(tenantId, cutoff);
    return Map.of("published", published, "giveUp", giveUp);
  }

  /**
   * 把指定 id 中、当前状态属于 FAILED/GIVE_UP 的事件 reset 回 NEW，让 OutboxForwarder 重新拾起重发。 其他状态（NEW / PUBLISHING
   * / PUBLISHED）的 id 静默跳过，不会被强制改写。
   *
   * @return 实际被 reset 的条数
   */
  @Transactional
  public int republish(String tenantId, List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return 0;
    }
    return outboxEventMapper.resetToNew(tenantId, ids, REPUBLISHABLE_FROM_STATUSES);
  }
}
