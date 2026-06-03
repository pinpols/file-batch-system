package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.logging.AuditLogConstants;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.rls.RlsTenantContextHolder;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceMetrics;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.BatchDayInstanceMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.service.LaunchService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 批次日结算调度器。
 *
 * <p>默认每 60 秒扫描一次处于 {@code CUTOFF} 或 {@code IN_FLIGHT} 状态的批次日实例， 根据关联任务实例的运行/失败/全量计数决定将批次日推进至
 * {@code IN_FLIGHT}、{@code FAILED} 或 {@code SETTLED}，并在结算为 FAILED 时按业务日历的追赶策略（AUTO/MANUAL_APPROVAL）
 * 自动发起补跑请求。ShedLock 锁名 {@code batch_day_settle}，最长持锁 3 分钟，最短持锁 30 秒。 优雅停机（draining）期间直接跳过执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchDaySettleScheduler {

  private static final List<String> TRACKED_STATUSES = List.of("CUTOFF", "IN_FLIGHT", "SETTLING");
  private static final String STATUS_SETTLING = "SETTLING";
  private static final String STATUS_IN_FLIGHT = "IN_FLIGHT";

  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final TriggerRequestMapper triggerRequestMapper;
  private final OrchestratorConfigCacheService configCacheService;
  private final LaunchService launchService;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final ObjectProvider<BatchDaySettleScheduler> selfProvider;
  private final BatchDateTimeSupport dateTimeSupport;

  @Scheduled(fixedDelayString = "${batch.batch-day.settle-scan-interval-millis:60000}")
  @SchedulerLock(name = "batch_day_settle", lockAtMostFor = "PT3M", lockAtLeastFor = "PT30S")
  public void scheduledSettle() {
    settle();
  }

  /** 扫描入口：不包事务，逐条候选委派到 {@link #settleOne} 独立短事务里。 */
  public void settle() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    List<BatchDayInstanceEntity> candidates =
        batchDayInstanceMapper.selectByDayStatusIn(TRACKED_STATUSES);
    if (candidates == null || candidates.isEmpty()) {
      return;
    }
    Instant now = dateTimeSupport.nowInstant();
    BatchDaySettleScheduler self = selfProvider.getObject();
    for (BatchDayInstanceEntity candidate : candidates) {
      if (candidate == null || candidate.id() == null || Boolean.TRUE.equals(candidate.frozen())) {
        continue;
      }
      String tenantId = candidate.tenantId();
      if (tenantId == null || tenantId.isBlank()) {
        continue;
      }
      try {
        // RLS Phase B：settleOne → claimSettling / finalizeSettling 均是 REQUIRES_NEW；holder 必须在
        // 代理调用前绑好，否则事务起点拿不到 app.tenant_id，下游 mapper 在严格策略下静默失败。
        // try/catch 必须包在 runWithTenant 外，保证 finally 清 ThreadLocal。
        RlsTenantContextHolder.runWithTenant(tenantId, () -> self.settleOne(candidate, now));
      } catch (OptimisticLockingFailureException conflict) {
        // @Version CAS 冲突：其他路径（reopen / 并发 settle）先写了，本轮跳过，下 tick 重扫。
        log.info(
            "batch day settle cas conflict; will retry next tick: tenantId={},"
                + " calendarCode={}, bizDate={}, msg={}",
            candidate.tenantId(),
            candidate.calendarCode(),
            candidate.bizDate(),
            conflict.getMessage());
      }
    }
  }

  /**
   * 单个候选的结算入口：根据当前状态分派 —— SETTLING 直接走 finalize（重入幂等）； CUTOFF / IN_FLIGHT 先 claim 再 finalize。
   *
   * <p>每一阶段都是独立 REQUIRES_NEW 短事务：claim（tx1）一旦提交，运维就能看到 SETTLING 状态；finalize（tx2） 即使崩溃，下一轮扫描会重新
   * finalize SETTLING 行（重读 metrics 后决定终态或回 IN_FLIGHT）。
   *
   * <p>必须是 {@code public}，且通过 self-proxy 调用才能被 Spring AOP 织入事务。
   */
  public void settleOne(BatchDayInstanceEntity candidate, Instant now) {
    BatchDaySettleScheduler self = selfProvider.getObject();
    if (STATUS_SETTLING.equals(candidate.dayStatus())) {
      self.finalizeSettling(
          candidate.tenantId(), candidate.calendarCode(), candidate.bizDate(), now);
      return;
    }
    if (!self.claimSettling(candidate, now)) {
      return;
    }
    self.finalizeSettling(candidate.tenantId(), candidate.calendarCode(), candidate.bizDate(), now);
  }

  /**
   * tx1：根据 metrics 判定 settle 入口动作。
   *
   * <ul>
   *   <li>active>0 且当前不是 IN_FLIGHT → 推进到 IN_FLIGHT，返回 false（无需 finalize）。
   *   <li>total<=0 → 返回 false（没东西好结算）。
   *   <li>否则 CAS 到 SETTLING，返回 true 让 finalize 接力。
   * </ul>
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claimSettling(BatchDayInstanceEntity candidate, Instant now) {
    BatchDayInstanceMetrics metrics =
        jobInstanceMapper.selectBatchDayMetrics(
            candidate.tenantId(), candidate.calendarCode(), candidate.bizDate());
    if (metrics == null) {
      return false;
    }
    long activeCount = value(metrics.getActiveCount());
    long totalCount = value(metrics.getTotalCount());
    if (activeCount > 0) {
      if (!STATUS_IN_FLIGHT.equals(candidate.dayStatus())) {
        BatchDayInstanceEntity to = candidate.withDayStatus(STATUS_IN_FLIGHT, now);
        casUpdate(to);
        appendBatchDayAuditLog(candidate, to, "IN_FLIGHT_BECAUSE_ACTIVE_INSTANCES");
      }
      return false;
    }
    if (totalCount <= 0L) {
      return false;
    }
    BatchDayInstanceEntity claimed = candidate.withDayStatus(STATUS_SETTLING, now);
    casUpdate(claimed);
    appendBatchDayAuditLog(candidate, claimed, "BATCH_DAY_SETTLING_CLAIMED");
    return true;
  }

  /**
   * tx2：从 SETTLING 落到终态（SETTLED / FAILED）；并发期间 active 又起来则回 IN_FLIGHT。 入口重读 DB 拿到 tx1 之后的 version 与
   * dayStatus，进程在 tx2 之前崩溃时下一轮重做也走同一路径。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finalizeSettling(
      String tenantId, String calendarCode, LocalDate bizDate, Instant now) {
    BatchDayInstanceEntity claimed =
        batchDayInstanceMapper.selectByTenantCalendarBizDate(tenantId, calendarCode, bizDate);
    if (claimed == null || !STATUS_SETTLING.equals(claimed.dayStatus())) {
      return;
    }
    BatchDayInstanceMetrics metrics =
        jobInstanceMapper.selectBatchDayMetrics(tenantId, calendarCode, bizDate);
    if (metrics == null) {
      return;
    }
    long activeCount = value(metrics.getActiveCount());
    long failedCount = value(metrics.getFailedCount());
    long totalCount = value(metrics.getTotalCount());
    if (activeCount > 0L) {
      BatchDayInstanceEntity to = claimed.withDayStatus(STATUS_IN_FLIGHT, now);
      casUpdate(to);
      appendBatchDayAuditLog(claimed, to, "SETTLING_REVERTED_TO_IN_FLIGHT");
      return;
    }
    if (totalCount <= 0L) {
      BatchDayInstanceEntity to = claimed.withDayStatus("CUTOFF", now);
      casUpdate(to);
      appendBatchDayAuditLog(claimed, to, "SETTLING_REVERTED_TO_CUTOFF");
      return;
    }
    if (failedCount > 0L) {
      BatchDayInstanceEntity to = claimed.withSettled("FAILED", now, now);
      casUpdate(to);
      appendBatchDayAuditLog(claimed, to, "BATCH_DAY_FAILED");
      // driveCatchUp 写 job_instance / outbox 等副作用,**必须等当前 REQUIRES_NEW 事务提交后**才执行;
      // 否则:本事务回滚会留下"batch_day 仍 SETTLING + catch-up job_instance 已写"的不一致,
      // 下一轮重做 finalizeSettling 再次触发 catch-up,造成同 candidate 重复 launch。
      // afterCommit 钩子保证只在 batch_day 真正落到 FAILED 后才发起 catch-up。
      final BatchDayInstanceEntity finalClaimed = claimed;
      final Instant finalNow = now;
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCommit() {
                driveCatchUp(finalClaimed, finalNow);
              }
            });
      } else {
        // 无 tx 上下文(单测 / 手动直调),退化为内联;生产路径不应走这里(@Transactional 保证有 tx)
        log.warn(
            "finalizeSettling invoked outside transaction context; running driveCatchUp inline");
        driveCatchUp(finalClaimed, finalNow);
      }
      log.info(
          "batch day settled as FAILED: tenantId={}, calendarCode={}, bizDate={}",
          claimed.tenantId(),
          claimed.calendarCode(),
          claimed.bizDate());
      return;
    }
    BatchDayInstanceEntity to = claimed.withSettled("SETTLED", now, now);
    casUpdate(to);
    appendBatchDayAuditLog(claimed, to, "BATCH_DAY_SETTLED");
    log.info(
        "batch day settled as SETTLED: tenantId={}, calendarCode={}, bizDate={}",
        claimed.tenantId(),
        claimed.calendarCode(),
        claimed.bizDate());
  }

  private long value(Long value) {
    return value == null ? 0L : value;
  }

  /** 替换原 {@code repository.save}：CAS 失败抛 OLF，被外层循环 catch 跳过本条候选下 tick 重扫。 */
  private void casUpdate(BatchDayInstanceEntity record) {
    int rows = batchDayInstanceMapper.updateWithCas(record);
    if (rows == 0) {
      throw new OptimisticLockingFailureException(
          "batch_day_instance version mismatch: id="
              + record.id()
              + ", version="
              + record.version());
    }
  }

  private void driveCatchUp(BatchDayInstanceEntity batchDay, Instant now) {
    BusinessCalendarEntity calendar =
        configCacheService.findEnabledBusinessCalendar(
            batchDay.tenantId(), batchDay.calendarCode());
    if (calendar == null
        || calendar.catchUpPolicy() == null
        || "NONE".equalsIgnoreCase(calendar.catchUpPolicy())) {
      return;
    }
    List<JobInstanceEntity> candidates =
        jobInstanceMapper.selectBatchDayCatchUpCandidates(
            batchDay.tenantId(), batchDay.calendarCode(), batchDay.bizDate());
    if (candidates == null || candidates.isEmpty()) {
      return;
    }
    for (JobInstanceEntity candidate : candidates) {
      if (candidate == null || candidate.getJobCode() == null || candidate.getJobCode().isBlank()) {
        continue;
      }
      String dedupKey = buildCatchUpDedupKey(batchDay, candidate);
      TriggerRequestEntity existing =
          triggerRequestMapper.selectByTenantAndDedupKey(batchDay.tenantId(), dedupKey);
      TriggerRequestEntity request = existing;
      if (request == null) {
        request = new TriggerRequestEntity();
        request.setTenantId(batchDay.tenantId());
        request.setRequestId(IdGenerator.newBusinessNo("catchup"));
        request.setTriggerType(TriggerType.CATCH_UP.code());
        request.setJobCode(candidate.getJobCode());
        request.setBizDate(batchDay.bizDate());
        request.setDedupKey(dedupKey);
        request.setRequestStatus("ACCEPTED");
        request.setTraceId(IdGenerator.newTraceId());
        triggerRequestMapper.insert(request);
      }
      if ("AUTO".equalsIgnoreCase(calendar.catchUpPolicy()) && isLaunchable(request)) {
        LaunchRequest launchRequest =
            LaunchRequest.builder()
                .tenantId(request.getTenantId())
                .jobCode(request.getJobCode())
                .bizDate(request.getBizDate())
                .triggerType(TriggerType.CATCH_UP)
                .requestId(request.getRequestId())
                .traceId(request.getTraceId())
                .params(buildCatchUpParams(batchDay, candidate, calendar, now))
                .build();
        LaunchResponse response = launchService.launch(launchRequest);
        log.info(
            "batch day catch-up launched: tenantId={}, calendarCode={}, bizDate={},"
                + " jobCode={}, requestId={}, instanceNo={}",
            batchDay.tenantId(),
            batchDay.calendarCode(),
            batchDay.bizDate(),
            candidate.getJobCode(),
            request.getRequestId(),
            response == null ? null : response.instanceNo());
      }
    }
  }

  private void appendBatchDayAuditLog(
      BatchDayInstanceEntity from, BatchDayInstanceEntity to, String reasonCode) {
    JobExecutionLogEntity audit = new JobExecutionLogEntity();
    audit.setTenantId(from.tenantId());
    audit.setJobInstanceId(null);
    audit.setJobPartitionId(null);
    audit.setLogLevel("INFO");
    audit.setLogType(AuditLogConstants.LOG_TYPE_AUDIT);
    audit.setTraceId(null);
    audit.setMessage("BATCH_DAY_INSTANCE_STATUS_CHANGED");
    audit.setDetailRef(AuditLogConstants.DETAIL_REF_BATCH_DAY_INSTANCE);
    audit.setExtraJson(
        JsonUtils.toJson(
            new LinkedHashMap<>() {
              {
                put("calendarCode", from.calendarCode());
                put("bizDate", from.bizDate() == null ? null : from.bizDate().toString());
                put("fromDayStatus", from.dayStatus());
                put("toDayStatus", to.dayStatus());
                put("reasonCode", reasonCode);
                put("operatorId", AuditLogConstants.OPERATOR_ID_SYSTEM_BATCH_DAY_SETTLE);
                put("operatorType", AuditLogConstants.OPERATOR_TYPE_SYSTEM);
                put("cutoffAt", to.cutoffAt() == null ? null : to.cutoffAt().toString());
                put("settledAt", to.settledAt() == null ? null : to.settledAt().toString());
              }
            }));
    jobExecutionLogMapper.insert(audit);
  }

  private boolean isLaunchable(TriggerRequestEntity request) {
    if (request == null) {
      return false;
    }
    String status = request.getRequestStatus();
    return status == null
        || (!"LAUNCHED".equalsIgnoreCase(status) && !"REJECTED".equalsIgnoreCase(status));
  }

  private String buildCatchUpDedupKey(
      BatchDayInstanceEntity batchDay, JobInstanceEntity candidate) {
    return batchDay.tenantId()
        + ":batch-day-catchup:"
        + batchDay.calendarCode()
        + ":"
        + batchDay.bizDate()
        + ":"
        + candidate.getJobCode();
  }

  private Map<String, Object> buildCatchUpParams(
      BatchDayInstanceEntity batchDay,
      JobInstanceEntity candidate,
      BusinessCalendarEntity calendar,
      Instant now) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("batchDayCatchUp", true);
    params.put("operationType", "BATCH_DAY_CATCH_UP");
    params.put("catchUpReason", "BATCH_DAY_FAILED");
    params.put("batchDayStatus", batchDay.dayStatus());
    params.put("batchDayCalendarCode", batchDay.calendarCode());
    params.put(
        "batchDayBizDate", batchDay.bizDate() == null ? null : batchDay.bizDate().toString());
    params.put("catchUpPolicy", calendar == null ? null : calendar.catchUpPolicy());
    params.put("catchUpRequestedAt", now.toString());
    params.put("sourceJobInstanceId", candidate == null ? null : candidate.getId());
    return params;
  }
}
