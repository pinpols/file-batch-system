package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.service.BatchDayTimePolicyResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * batch_day_instance 自动切换：OPEN -> CUTOFF（在 cutoff_time 之后）。
 *
 * <p>该状态机缺失会导致 late arrival 路由永远无法生效，因此必须补齐。
 *
 * <p>事务边界：扫描循环本身不开事务（避免长事务 + 与 @SchedulerLock AOP 顺序歧义）， 每个候选 batch_day_instance 用 {@link
 * TransactionTemplate} 单条 short tx 处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchDayCutoffScheduler {

  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final OrchestratorConfigCacheService configCacheService;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final BatchDayTimePolicyResolver timePolicyResolver;
  private final BatchDateTimeSupport dateTimeSupport;
  private final PlatformTransactionManager transactionManager;

  @Scheduled(fixedDelayString = "${batch.batch-day.cutoff-scan-interval-millis:60000}")
  // 命名加模块前缀：与 trigger 同名 ShedLock 会跨服务互斥；两侧业务逻辑不同不应互斥。
  @SchedulerLock(
      name = "orchestrator_batch_day_cutoff",
      lockAtMostFor = "PT2M",
      lockAtLeastFor = "PT20S")
  public void scheduledAdvance() {
    advance();
  }

  public void advance() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    Instant now = dateTimeSupport.nowInstant();
    List<String> tracked = List.of("OPEN");

    List<BatchDayInstanceEntity> candidates = batchDayInstanceMapper.selectByDayStatusIn(tracked);
    if (candidates == null || candidates.isEmpty()) {
      return;
    }

    for (BatchDayInstanceEntity candidate : candidates) {
      if (candidate == null
          || candidate.tenantId() == null
          || candidate.calendarCode() == null
          || candidate.bizDate() == null
          || Boolean.TRUE.equals(candidate.frozen())) {
        continue;
      }
      String tenantId = candidate.tenantId();
      if (tenantId.isBlank()) {
        continue;
      }
      // RLS Phase B：resolveCutoffAt 走 config cache（可能回源 DB）+ applyCutoff 在 short tx 内写
      // batch_day_instance + audit log；统一绑租户后再处理，保证两段都在 holder 作用域内。
      RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> {
            Instant cutoffAt = candidate.cutoffAt();
            if (cutoffAt == null) {
              cutoffAt =
                  resolveCutoffAt(
                      candidate.tenantId(), candidate.calendarCode(), candidate.bizDate());
            }
            if (cutoffAt == null) {
              return;
            }
            if (!now.isBefore(cutoffAt)) {
              Instant cutoffAtFinal = cutoffAt;
              // 每个候选行一笔 short tx：CAS 更新 + 审计日志同事务原子。
              TransactionTemplate tx = new TransactionTemplate(transactionManager);
              tx.executeWithoutResult(status -> applyCutoff(candidate, cutoffAtFinal, now));
            }
          });
    }
  }

  private void applyCutoff(BatchDayInstanceEntity candidate, Instant cutoffAt, Instant now) {
    BatchDayInstanceEntity updated = candidate.withCutoff(cutoffAt, now);
    int rows = batchDayInstanceMapper.updateWithCas(updated);
    if (rows == 0) {
      // CAS 冲突：另一路径（settle / reopen）抢先写入，本轮跳过该候选不阻塞其他候选
      log.debug(
          "batch day cutoff cas conflict; skip: tenantId={}, calendarCode={}, bizDate={}",
          candidate.tenantId(),
          candidate.calendarCode(),
          candidate.bizDate());
      return;
    }
    appendAuditLog(candidate, updated, "CUTOFF_REACHED", now);
    log.info(
        "batch day advanced to CUTOFF: tenantId={}, calendarCode={}, bizDate={}",
        candidate.tenantId(),
        candidate.calendarCode(),
        candidate.bizDate());
  }

  private Instant resolveCutoffAt(String tenantId, String calendarCode, LocalDate bizDate) {
    BusinessCalendarEntity calendar =
        configCacheService.findEnabledBusinessCalendar(tenantId, calendarCode);
    if (calendar == null) {
      return null;
    }
    return timePolicyResolver.resolveCutoffAt(calendar, bizDate);
  }

  private void appendAuditLog(
      BatchDayInstanceEntity from, BatchDayInstanceEntity to, String reasonCode, Instant now) {
    JobExecutionLogEntity logEntity = new JobExecutionLogEntity();
    logEntity.setTenantId(from.tenantId());
    logEntity.setJobInstanceId(null);
    logEntity.setJobPartitionId(null);
    logEntity.setLogLevel("INFO");
    logEntity.setLogType(AuditLogConstants.LOG_TYPE_AUDIT);
    logEntity.setTraceId(null);
    logEntity.setMessage("BATCH_DAY_INSTANCE_STATUS_CHANGED");
    logEntity.setDetailRef(AuditLogConstants.DETAIL_REF_BATCH_DAY_INSTANCE);
    LinkedHashMap<String, Object> extra = new LinkedHashMap<>();
    extra.put("calendarCode", from.calendarCode());
    extra.put("bizDate", from.bizDate() == null ? null : from.bizDate().toString());
    extra.put("fromDayStatus", from.dayStatus());
    extra.put("toDayStatus", to.dayStatus());
    extra.put("reasonCode", reasonCode);
    extra.put("operatorId", AuditLogConstants.OPERATOR_ID_SYSTEM_BATCH_DAY_CUTOFF);
    extra.put("operatorType", AuditLogConstants.OPERATOR_TYPE_SYSTEM);
    extra.put("cutoffAt", to.cutoffAt() == null ? null : to.cutoffAt().toString());
    extra.put("at", now.toString());
    logEntity.setExtraJson(JsonUtils.toJson(extra));
    jobExecutionLogMapper.insert(logEntity);
  }
}
