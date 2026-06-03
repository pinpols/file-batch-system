package com.example.batch.orchestrator.application.service.governance;

import com.example.batch.common.logging.AuditLogConstants;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 运维应用服务:承接 console 通过 HTTP 转发过来的 outbox cleanup / republish 操作。
 *
 * <p>由于 CLAUDE.md「Orchestrator 是唯一状态主机」硬约束,console-api 不能直接 UPDATE/DELETE outbox_event;改由本服务在
 * orchestrator 内部 @Transactional 边界里执行。
 *
 * <p>P1-3 (2026-06-03 deep-scan-be-business-ops): 支持 dryRun(只查 count 不改) + audit(operatorId /
 * reason 写 job_execution_log)。
 */
@Service
@RequiredArgsConstructor
public class OutboxOpsApplicationService {

  private static final List<String> REPUBLISHABLE_FROM_STATUSES = List.of("FAILED", "GIVE_UP");

  private final OutboxEventMapper outboxEventMapper;
  private final JobExecutionLogMapper jobExecutionLogMapper;

  /**
   * 删除指定租户中 PUBLISHED + GIVE_UP、且 updated_at 早于 retainDays 的事件。dryRun=true 时只 count 不删。
   *
   * @return key=published / giveUp 的删除条数;dryRun 时是预估候选数
   */
  @Transactional
  public Map<String, Integer> cleanup(
      String tenantId, int retainDays, boolean dryRun, String operatorId, String reason) {
    Instant cutoff = BatchDateTimeSupport.utcNow().minus(retainDays, ChronoUnit.DAYS);
    int published;
    int giveUp;
    if (dryRun) {
      published = outboxEventMapper.countPublishedBefore(tenantId, cutoff);
      giveUp = outboxEventMapper.countGiveUpBefore(tenantId, cutoff);
    } else {
      published = outboxEventMapper.deletePublishedBefore(tenantId, cutoff);
      giveUp = outboxEventMapper.deleteGiveUpBefore(tenantId, cutoff);
    }
    appendOutboxAudit(
        tenantId,
        AuditLogConstants.AUDIT_OP_OUTBOX_CLEANUP,
        operatorId,
        reason,
        Map.of(
            "retainDays", retainDays,
            "cutoff", cutoff.toString(),
            "dryRun", dryRun,
            "publishedAffected", published,
            "giveUpAffected", giveUp));
    return Map.of("published", published, "giveUp", giveUp);
  }

  /** 重投递:把指定 id 中、FAILED/GIVE_UP 的事件 reset 回 NEW;dryRun=true 时只 count 不改。 */
  @Transactional
  public int republish(
      String tenantId, List<Long> ids, boolean dryRun, String operatorId, String reason) {
    if (ids == null || ids.isEmpty()) {
      return 0;
    }
    int affected;
    if (dryRun) {
      affected = outboxEventMapper.countResettable(tenantId, ids, REPUBLISHABLE_FROM_STATUSES);
    } else {
      affected = outboxEventMapper.resetToNew(tenantId, ids, REPUBLISHABLE_FROM_STATUSES);
    }
    appendOutboxAudit(
        tenantId,
        AuditLogConstants.AUDIT_OP_OUTBOX_REPUBLISH,
        operatorId,
        reason,
        Map.of(
            "requestedIds", ids.size(),
            "affected", affected,
            "dryRun", dryRun));
    return affected;
  }

  // ── 向后兼容入口(老 controller 路径,无 dryRun/operatorId)──────────────────────────
  @Transactional
  public Map<String, Integer> cleanup(String tenantId, int retainDays) {
    return cleanup(tenantId, retainDays, false, null, null);
  }

  @Transactional
  public int republish(String tenantId, List<Long> ids) {
    return republish(tenantId, ids, false, null, null);
  }

  private void appendOutboxAudit(
      String tenantId,
      String operation,
      String operatorId,
      String reason,
      Map<String, Object> extras) {
    if (jobExecutionLogMapper == null) {
      return;
    }
    JobExecutionLogEntity audit = new JobExecutionLogEntity();
    audit.setTenantId(tenantId);
    audit.setLogLevel("INFO");
    audit.setLogType(AuditLogConstants.LOG_TYPE_AUDIT);
    audit.setMessage(operation);
    audit.setDetailRef(AuditLogConstants.DETAIL_REF_OUTBOX_EVENT);
    Map<String, Object> extra = new LinkedHashMap<>(extras);
    extra.put(
        "operatorId",
        Texts.hasText(operatorId) ? operatorId : AuditLogConstants.OPERATOR_ID_SYSTEM);
    extra.put(
        "operatorType",
        Texts.hasText(operatorId)
            ? AuditLogConstants.OPERATOR_TYPE_REQUEST
            : AuditLogConstants.OPERATOR_TYPE_SYSTEM);
    extra.put("reason", reason);
    audit.setExtraJson(JsonUtils.toJson(extra));
    jobExecutionLogMapper.insert(audit);
  }
}
