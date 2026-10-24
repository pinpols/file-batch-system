package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.orchestrator.domain.query.ExpectedWorkOccurrenceDueExpectedWithoutMissingAlertQuery;


import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.query.ExpectedWorkOccurrenceOverride;
import io.github.pinpols.batch.orchestrator.domain.view.BatchDayInstanceMetrics;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.mapper.CalendarDependencyWaitMapper;
import io.github.pinpols.batch.orchestrator.mapper.ExpectedWorkOccurrenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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

  private static final String STATUS_SETTLING = "SETTLING";
  private static final String STATUS_IN_FLIGHT = "IN_FLIGHT";
  private static final List<String> TRACKED_STATUSES =
      List.of("CUTOFF", STATUS_IN_FLIGHT, STATUS_SETTLING);
  private static final int FAILED_CATCH_UP_RECOVERY_LIMIT = 100;

  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final BatchDayWaitingLaunchMapper batchDayWaitingLaunchMapper;
  private final JobDependencyWaitingLaunchMapper jobDependencyWaitingLaunchMapper;
  private final CalendarDependencyWaitMapper calendarDependencyWaitMapper;
  private final ExpectedWorkOccurrenceMapper expectedWorkOccurrenceMapper;
  private final AlertEventService alertEventService;
  private final JobInstanceMapper jobInstanceMapper;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final TriggerRequestMapper triggerRequestMapper;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final OrchestratorConfigCacheService configCacheService;
  private final LaunchApplicationService launchApplicationService;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final BatchDateTimeSupport dateTimeSupport;
  private final PlatformTransactionManager transactionManager;

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
      recoverFailedCatchUps();
      return;
    }
    Instant now = dateTimeSupport.nowInstant();
    for (BatchDayInstanceEntity candidate : candidates) {
      boolean batchDayCannotBeSettled =
          candidate == null || candidate.id() == null || Boolean.TRUE.equals(candidate.frozen());
      if (batchDayCannotBeSettled) {
        continue;
      }
      String tenantId = candidate.tenantId();
      if (tenantId == null || tenantId.isBlank()) {
        continue;
      }
      try {
        // RLS Phase B：settleOne 内 claim/finalize 均是 REQUIRES_NEW；holder 必须在
        // 显式短事务开始前绑好，否则事务起点拿不到 app.tenant_id，下游 mapper 在严格策略下静默失败。
        // try/catch 必须包在 runWithTenant 外，保证 finally 清 ThreadLocal。
        RlsTenantContextHolder.runWithTenant(tenantId, () -> settleOne(candidate, now));
      } catch (OptimisticLockingFailureException conflict) {
        // @Version CAS 冲突：其他路径（reopen / 并发 settle）先写了，本轮跳过，下 tick 重扫。
        log.info(
            "batch day settle cas conflict; will retry next tick: tenantId={},"
                + " calendarCode={}, bizDate={}, msg={}",
            candidate.tenantId(),
            candidate.calendarCode(),
            candidate.bizDate(),
            SwallowedExceptionLogger.summary(conflict));
      }
    }
    recoverFailedCatchUps();
  }

  /**
   * FAILED 状态已在主结算事务中提交，afterCommit 派发可能遇到进程退出或临时异常。
   * 重新扫描仍缺少补跑请求或 AUTO 请求尚未 LAUNCHED 的日期；请求 dedup key 使重复派发收敛。
   */
  private void recoverFailedCatchUps() {
    List<BatchDayInstanceEntity> failedDays =
        batchDayInstanceMapper.selectFailedDaysNeedingCatchUp(FAILED_CATCH_UP_RECOVERY_LIMIT);
    if (EmptyChecks.isNull(failedDays) || EmptyChecks.isEmpty(failedDays)) return;
    Instant now = dateTimeSupport.nowInstant();
    for (BatchDayInstanceEntity failedDay : failedDays) {
      if (EmptyChecks.isNull(failedDay) || !Texts.hasText(failedDay.tenantId())) continue;
      RlsTenantContextHolder.runWithTenant(
          failedDay.tenantId(), () -> driveCatchUp(failedDay, now));
    }
  }

  /**
   * 单个候选的结算入口：根据当前状态分派 —— SETTLING 直接走 finalize（重入幂等）； CUTOFF / IN_FLIGHT 先 claim 再 finalize。
   *
   * <p>每一阶段都是独立 REQUIRES_NEW 短事务：claim（tx1）一旦提交，运维就能看到 SETTLING 状态；finalize（tx2） 即使崩溃，下一轮扫描会重新
   * finalize SETTLING 行（重读 metrics 后决定终态或回 IN_FLIGHT）。
   *
   */
  private void settleOne(BatchDayInstanceEntity candidate, Instant now) {
    if (STATUS_SETTLING.equals(candidate.dayStatus())) {
      finalizeInNewTransaction(
          candidate.tenantId(), candidate.calendarCode(), candidate.bizDate(), now);
      return;
    }
    if (!claimInNewTransaction(candidate, now)) {
      return;
    }
    finalizeInNewTransaction(
        candidate.tenantId(), candidate.calendarCode(), candidate.bizDate(), now);
  }

  private boolean claimInNewTransaction(BatchDayInstanceEntity candidate, Instant now) {
    Boolean claimed = newSettlementTransaction().execute(transactionStatus -> {
      expectedWorkOccurrenceMapper.setTenantContext(candidate.tenantId());
      return claimSettling(candidate, now);
    });
    return Boolean.TRUE.equals(claimed);
  }

  private void finalizeInNewTransaction(
      String tenantId, String calendarCode, LocalDate bizDate, Instant now) {
    newSettlementTransaction().executeWithoutResult(transactionStatus -> {
      expectedWorkOccurrenceMapper.setTenantContext(tenantId);
      finalizeSettling(tenantId, calendarCode, bizDate, now);
    });
  }

  private TransactionTemplate newSettlementTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction;
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
  private boolean claimSettling(BatchDayInstanceEntity candidate, Instant now) {
    batchDayInstanceMapper.acquireSettlementFence(
        candidate.tenantId(), candidate.calendarCode(), candidate.bizDate());
    candidate = batchDayInstanceMapper.selectByTenantCalendarBizDate(
        candidate.tenantId(), candidate.calendarCode(), candidate.bizDate());
    if (EmptyChecks.isNull(candidate) || Boolean.TRUE.equals(candidate.frozen())) {
      return false;
    }
    // 等待每日围栏期间，扫描候选项可能已经过期。只领取仍
    // 符合条件的记录；其他扫描可能已经领取或完成该业务日。
    if (!"CUTOFF".equals(candidate.dayStatus())
        && !STATUS_IN_FLIGHT.equals(candidate.dayStatus())) {
      return false;
    }
    alertOverdueExpectedWork(candidate, now);
    BatchDayInstanceMetrics metrics = jobInstanceMapper.selectBatchDayMetrics(
        candidate.tenantId(), candidate.calendarCode(), candidate.bizDate());
    if (metrics == null) {
      return false;
    }
    long activeCount = value(metrics.getActiveCount());
    long totalCount = value(metrics.getTotalCount());
    boolean unresolvedWork = hasExpectedOrUnresolvedWork(
        candidate.tenantId(), candidate.calendarCode(), candidate.bizDate(), candidate.cutoffAt());
    if (activeCount > 0) {
      if (!STATUS_IN_FLIGHT.equals(candidate.dayStatus())) {
        BatchDayInstanceEntity to = candidate.withDayStatus(STATUS_IN_FLIGHT, now);
        casUpdate(to);
        appendBatchDayAuditLog(candidate, to, "IN_FLIGHT_BECAUSE_ACTIVE_INSTANCES");
      }
      return false;
    }
    if (totalCount <= 0L) {
      boolean emptyCutoffDayCanSettle =
          "CUTOFF".equals(candidate.dayStatus()) && !unresolvedWork;
      if (emptyCutoffDayCanSettle) {
        BatchDayInstanceEntity settled = candidate.withSettled("SETTLED", now, now);
        casUpdate(settled);
        appendBatchDayAuditLog(candidate, settled, "AUTO_EMPTY_NO_EXPECTED_WORK");
      }
      return false;
    }
    if (unresolvedWork) {
      if (!STATUS_IN_FLIGHT.equals(candidate.dayStatus())) {
        BatchDayInstanceEntity waiting = candidate.withDayStatus(STATUS_IN_FLIGHT, now);
        casUpdate(waiting);
        appendBatchDayAuditLog(candidate, waiting, "IN_FLIGHT_BECAUSE_EXPECTED_WORK_REMAINS");
      }
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
  private void finalizeSettling(
      String tenantId, String calendarCode, LocalDate bizDate, Instant now) {
    batchDayInstanceMapper.acquireSettlementFence(tenantId, calendarCode, bizDate);
    BatchDayInstanceEntity claimed =
        batchDayInstanceMapper.selectByTenantCalendarBizDate(tenantId, calendarCode, bizDate);
    if (claimed == null || !STATUS_SETTLING.equals(claimed.dayStatus())) {
      return;
    }
    alertOverdueExpectedWork(claimed, now);
    BatchDayInstanceMetrics metrics =
        jobInstanceMapper.selectBatchDayMetrics(tenantId, calendarCode, bizDate);
    if (metrics == null) {
      return;
    }
    long activeCount = value(metrics.getActiveCount());
    long failedCount = value(metrics.getFailedCount());
    long totalCount = value(metrics.getTotalCount());
    boolean unresolvedWork =
        hasExpectedOrUnresolvedWork(tenantId, calendarCode, bizDate, claimed.cutoffAt());
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
    if (unresolvedWork) {
      BatchDayInstanceEntity to = claimed.withDayStatus(STATUS_IN_FLIGHT, now);
      casUpdate(to);
      appendBatchDayAuditLog(claimed, to, "SETTLING_REVERTED_BECAUSE_EXPECTED_WORK_REMAINS");
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
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
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

  private boolean hasExpectedOrUnresolvedWork(
      String tenantId, String calendarCode, LocalDate bizDate, Instant cutoffAt) {
    return expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
                tenantId, calendarCode, bizDate)
            > 0
        || calendarDependencyWaitMapper.countWaitingByTenantCalendarBizDate(
                tenantId, calendarCode, bizDate)
            > 0
        || EmptyChecks.isNotEmpty(batchDayWaitingLaunchMapper.selectWaitingByCalendarBizDate(
            tenantId, calendarCode, bizDate, 1))
        || jobDependencyWaitingLaunchMapper.countWaitingForCalendarBizDate(
                tenantId, calendarCode, bizDate)
            > 0
        || triggerRequestMapper.countUnlaunchedForCalendarBizDate(
                tenantId,
                calendarCode,
                bizDate,
                List.of("PENDING", "PROCESSING", "ACCEPTED", "WAITING"))
            > 0
        || batchDayInstanceMapper.countBlockingScheduledOccurrences(tenantId, calendarCode, bizDate)
            > 0
        || batchDayInstanceMapper.countUncoveredScheduledDefinitions(
                tenantId, calendarCode, cutoffAt)
            > 0
        || batchDayInstanceMapper.countUntrackedTriggerDefinitions(tenantId, calendarCode) > 0;
  }

  private void alertOverdueExpectedWork(BatchDayInstanceEntity batchDay, Instant now) {
    ExpectedWorkOccurrenceDueExpectedWithoutMissingAlertQuery expectedWorkOccurrenceDueExpectedWithoutMissingAlertQuery = ExpectedWorkOccurrenceDueExpectedWithoutMissingAlertQuery.builder()
        .tenantId(batchDay.tenantId())
        .calendarCode(batchDay.calendarCode())
        .bizDate(batchDay.bizDate())
        .now(now)
        .limit(100)
        .build();
    List<ExpectedWorkOccurrenceOverride> overdue =
        expectedWorkOccurrenceMapper.selectDueExpectedWithoutMissingAlert(
            expectedWorkOccurrenceDueExpectedWithoutMissingAlertQuery);
    if (EmptyChecks.isNull(overdue) || EmptyChecks.isEmpty(overdue)) {
      return;
    }
    for (ExpectedWorkOccurrenceOverride occurrence : overdue) {
      if (expectedWorkOccurrenceMapper.claimMissingInputAlert(
              batchDay.tenantId(), occurrence.id(), now)
          != 1) {
        continue;
      }
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("expectedWorkOccurrenceId", occurrence.id().toString());
      detail.put("workflowDefinitionId", occurrence.workflowDefinitionId().toString());
      detail.put("calendarCode", batchDay.calendarCode());
      detail.put("bizDate", occurrence.bizDate().toString());
      detail.put("sourceType", occurrence.sourceType());
      detail.put("sourceKey", occurrence.sourceKey());
      detail.put("generation", occurrence.generation());
      detail.put("expectedAt", EmptyChecks.isNull(occurrence.expectedAt()) ? null : occurrence.expectedAt().toString());
      detail.put("dueAt", occurrence.dueAt().toString());
      detail.put("triggerRequestId", occurrence.triggerRequestId());
      String detailJson = JsonUtils.toJson(detail);
      AlertEmitRequest missingInputAlert = AlertEmitRequest.builder()
          .tenantId(batchDay.tenantId())
          .serviceName("batch-orchestrator")
          .alertType("BATCH_EXPECTED_WORK_MISSING")
          .severity("ERROR")
          .title("expected input has not arrived by its due time")
          .detailJson(detailJson)
          .traceId(null)
          .resourceKey("expected_work_occurrence:" + occurrence.id())
          .build();
      alertEventService.emit(missingInputAlert);
    }
  }

  private long value(Long value) {
    return value == null ? 0L : value;
  }

  /** 替换原 {@code repository.save}：CAS 失败抛 OLF，被外层循环 catch 跳过本条候选下 tick 重扫。 */
  private void casUpdate(BatchDayInstanceEntity batchDay) {
    int rows = batchDayInstanceMapper.updateWithCas(batchDay);
    if (rows == 0) {
      throw new OptimisticLockingFailureException("batch_day_instance version mismatch: id="
          + batchDay.id()
          + ", version="
          + batchDay.version());
    }
  }

  private void driveCatchUp(BatchDayInstanceEntity batchDay, Instant now) {
    BusinessCalendarEntity calendar = configCacheService.findEnabledBusinessCalendar(
        batchDay.tenantId(), batchDay.calendarCode());
    boolean calendarCatchupIsDisabled = calendar == null
        || calendar.catchUpPolicy() == null
        || "NONE".equalsIgnoreCase(calendar.catchUpPolicy());
    if (calendarCatchupIsDisabled) {
      return;
    }
    List<JobInstanceEntity> candidates = jobInstanceMapper.selectBatchDayCatchUpCandidates(
        batchDay.tenantId(), batchDay.calendarCode(), batchDay.bizDate());
    if (candidates == null || candidates.isEmpty()) {
      return;
    }
    for (JobInstanceEntity candidate : candidates) {
      if (candidate == null
          || candidate.getJobCode() == null
          || candidate.getJobCode().isBlank()) {
        continue;
      }
      JobDefinitionEntity jobDefinition =
          configCacheService.findEnabledJobDefinition(batchDay.tenantId(), candidate.getJobCode());
      WorkflowDefinitionEntity workflow =
          EmptyChecks.isNull(jobDefinition) || EmptyChecks.isNull(jobDefinition.id())
              ? null
              : workflowDefinitionMapper.selectFirstByTenantAndSingleJobDefinitionIdAndEnabled(
                  batchDay.tenantId(), jobDefinition.id(), true);
      if (EmptyChecks.isNull(workflow) || EmptyChecks.isNull(workflow.id())) {
        persistUnlinkedCatchUpRequest(batchDay, candidate);
        log.error(
            "batch day catch-up rejected because managed single-job Workflow is missing:"
                + " tenantId={}, calendarCode={}, bizDate={}, jobCode={}, jobDefinitionId={}",
            batchDay.tenantId(),
            batchDay.calendarCode(),
            batchDay.bizDate(),
            candidate.getJobCode(),
            EmptyChecks.isNull(jobDefinition) ? null : jobDefinition.id());
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
        request.setWorkflowDefinitionId(workflow.id());
        request.setBizDate(batchDay.bizDate());
        request.setDedupKey(dedupKey);
        request.setRequestStatus(TriggerRequestStatus.ACCEPTED.code());
        request.setTraceId(IdGenerator.newTraceId());
        Map<String, Object> params = buildCatchUpParams(batchDay, candidate, calendar, now);
        params.put("workflowVersion", workflow.version());
        LaunchRequest acceptedLaunch = LaunchRequest.builder()
            .tenantId(request.getTenantId())
            .workflowDefinitionId(request.getWorkflowDefinitionId())
            .bizDate(request.getBizDate())
            .triggerType(TriggerType.CATCH_UP)
            .requestId(request.getRequestId())
            .traceId(request.getTraceId())
            .dataIntervalStart(candidate.getDataIntervalStart())
            .dataIntervalEnd(candidate.getDataIntervalEnd())
            .params(params)
            .build();
        request.setLaunchPayload(JsonUtils.toJson(acceptedLaunch));
        triggerRequestMapper.insert(request);
      }
      if (!workflow.id().equals(request.getWorkflowDefinitionId())) {
        triggerRequestMapper.updateAcceptance(
            request.getTenantId(),
            request.getRequestId(),
            TriggerRequestStatus.REJECTED.code(),
            null);
        log.error(
            "batch day catch-up rejected because existing request is not pinned to the managed"
                + " single-job Workflow: tenantId={}, requestId={}, jobCode={}, workflowDefinitionId={}",
            batchDay.tenantId(),
            request.getRequestId(),
            candidate.getJobCode(),
            request.getWorkflowDefinitionId());
        continue;
      }
      if ("AUTO".equalsIgnoreCase(calendar.catchUpPolicy()) && isLaunchable(request)) {
        // 重复扫描复用首次接受时冻结的快照，包括原始 interval。
        LaunchRequest launchRequest = readCatchUpSnapshot(request);
        if (EmptyChecks.isNull(launchRequest)) {
          continue;
        }
        LaunchResponse response = launchApplicationService.launchFromTrustedQueue(launchRequest);
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

  private LaunchRequest readCatchUpSnapshot(TriggerRequestEntity request) {
    LaunchRequest original = null;
    try {
      if (Texts.hasText(request.getLaunchPayload())) {
        original = JsonUtils.fromJsonStrict(request.getLaunchPayload(), LaunchRequest.class);
      }
    } catch (IllegalArgumentException invalidPayload) {
      SwallowedExceptionLogger.warn(
          BatchDaySettleScheduler.class, "batch-day-catch-up-snapshot-invalid", invalidPayload);
    }
    boolean snapshotMatchesCatchUpRequest = EmptyChecks.isNotNull(original)
        && Objects.equals(original.tenantId(), request.getTenantId())
        && Objects.equals(original.requestId(), request.getRequestId())
        && Objects.equals(original.workflowDefinitionId(), request.getWorkflowDefinitionId())
        && Objects.equals(original.bizDate(), request.getBizDate())
        && EmptyChecks.isNull(original.jobCode())
        && original.triggerType() == TriggerType.CATCH_UP;
    if (snapshotMatchesCatchUpRequest) {
      return original;
    }
    triggerRequestMapper.updateAcceptance(
        request.getTenantId(), request.getRequestId(), TriggerRequestStatus.REJECTED.code(), null);
    log.error(
        "batch day catch-up rejected because original snapshot is missing or invalid: tenantId={}, requestId={}",
        request.getTenantId(),
        request.getRequestId());
    return null;
  }

  /** 记录可见的拒绝结果，不得绕过规范的 Workflow 关联。 */
  private void persistUnlinkedCatchUpRequest(
      BatchDayInstanceEntity batchDay, JobInstanceEntity candidate) {
    String dedupKey = buildCatchUpDedupKey(batchDay, candidate);
    TriggerRequestEntity existing =
        triggerRequestMapper.selectByTenantAndDedupKey(batchDay.tenantId(), dedupKey);
    if (EmptyChecks.isNotNull(existing)) {
      triggerRequestMapper.updateAcceptance(
          existing.getTenantId(),
          existing.getRequestId(),
          TriggerRequestStatus.REJECTED.code(),
          null);
      return;
    }
    TriggerRequestEntity request = new TriggerRequestEntity();
    request.setTenantId(batchDay.tenantId());
    request.setRequestId(IdGenerator.newBusinessNo("catchup"));
    request.setTriggerType(TriggerType.CATCH_UP.code());
    request.setJobCode(candidate.getJobCode());
    request.setBizDate(batchDay.bizDate());
    request.setDedupKey(dedupKey);
    request.setRequestStatus(TriggerRequestStatus.REJECTED.code());
    request.setTraceId(IdGenerator.newTraceId());
    triggerRequestMapper.insert(request);
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
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("calendarCode", from.calendarCode());
    extra.put("bizDate", from.bizDate() == null ? null : from.bizDate().toString());
    extra.put("fromDayStatus", from.dayStatus());
    extra.put("toDayStatus", to.dayStatus());
    extra.put("reasonCode", reasonCode);
    extra.put("operatorId", AuditLogConstants.OPERATOR_ID_SYSTEM_BATCH_DAY_SETTLE);
    extra.put("operatorType", AuditLogConstants.OPERATOR_TYPE_SYSTEM);
    extra.put("cutoffAt", to.cutoffAt() == null ? null : to.cutoffAt().toString());
    extra.put("settledAt", to.settledAt() == null ? null : to.settledAt().toString());
    String extraJson = JsonUtils.toJson(extra);
    audit.setExtraJson(extraJson);
    jobExecutionLogMapper.insert(audit);
  }

  private boolean isLaunchable(TriggerRequestEntity request) {
    if (request == null) {
      return false;
    }
    String status = request.getRequestStatus();
    return status == null
        || (!TriggerRequestStatus.LAUNCHED.code().equalsIgnoreCase(status)
            && !TriggerRequestStatus.REJECTED.code().equalsIgnoreCase(status));
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
        "batchDayBizDate",
        batchDay.bizDate() == null ? null : batchDay.bizDate().toString());
    params.put("catchUpPolicy", calendar == null ? null : calendar.catchUpPolicy());
    params.put("catchUpRequestedAt", now.toString());
    params.put("sourceJobInstanceId", candidate == null ? null : candidate.getId());
    return params;
  }
}
