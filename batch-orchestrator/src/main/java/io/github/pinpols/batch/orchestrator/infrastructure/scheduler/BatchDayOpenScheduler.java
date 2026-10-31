package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.batchday.ExternalEventExpectationService;
import io.github.pinpols.batch.orchestrator.application.service.batchday.FileGroupExpectationService;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.config.BatchDayScanProperties;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayCatchupControlEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.CalendarDependencyEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.CalendarDependencyWaitEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.DisasterDayOverrideEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.param.BatchDayInstanceAdvanceOpenCursorParam;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.infrastructure.metrics.BatchDayCatchupMetrics;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayCatchupControlMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayPlanCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.CalendarDependencyMapper;
import io.github.pinpols.batch.orchestrator.mapper.CalendarDependencyWaitMapper;
import io.github.pinpols.batch.orchestrator.mapper.DisasterDayOverrideMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.service.BatchDayTimePolicyResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 主动打开批次日实例。
 *
 * <p>触发链路仍保留 {@code LaunchBatchDayService} 的 upsert 回退；本调度器负责让日切后的 {@code batch_day_instance}
 * 在没有作业触发时也先成为可观察、可门闩判断的一等运行对象。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchDayOpenScheduler {

  private static final LocalTime DEFAULT_CUTOFF_TIME = LocalTime.of(6, 0);
  private static final List<String> UNLAUNCHED_REQUEST_STATUSES = List.of(
      TriggerRequestStatus.PENDING.code(),
      TriggerRequestStatus.PROCESSING.code(),
      TriggerRequestStatus.ACCEPTED.code(),
      TriggerRequestStatus.WAITING.code());

  private final BusinessCalendarMapper businessCalendarMapper;
  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final BatchDayCatchupControlMapper catchupControlMapper;
  private final BatchDayPlanCalendarMapper planCalendarMapper;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final BatchTimezoneProvider timezoneProvider;
  private final BatchDayTimePolicyResolver timePolicyResolver;
  private final BatchDateTimeSupport dateTimeSupport;
  // ADR-023 Stage 3: 跨 calendar 串联依赖检查
  private final CalendarDependencyMapper calendarDependencyMapper;
  private final CalendarDependencyWaitMapper calendarDependencyWaitMapper;
  private final FileGroupExpectationService fileGroupExpectationService;
  private final ExternalEventExpectationService externalEventExpectationService;
  private final AlertEventService alertEventService;
  private final BatchDayCatchupMetrics catchupMetrics;
  // ADR-023 Stage 4: 灾难日热切换检查
  private final DisasterDayOverrideMapper disasterDayOverrideMapper;
  private final TriggerRequestMapper triggerRequestMapper;
  // 事务边界：扫描循环本身不开事务（避免长事务 + 与 @SchedulerLock AOP 顺序歧义），
  // 每个 calendar 通过 TransactionTemplate 使用 short tx 收敛依赖等待、batch_day_instance 和审计日志。
  private final PlatformTransactionManager transactionManager;
  private final BatchDayScanProperties batchDayScanProperties;

  @Scheduled(fixedDelayString = "${batch.batch-day.open-scan-interval-millis:60000}")
  @SchedulerLock(name = "batch_day_open", lockAtMostFor = "PT2M", lockAtLeastFor = "PT15S")
  public void scheduledOpen() {
    openDueBatchDays(dateTimeSupport.nowInstant());
  }

  public void openDueBatchDays(Instant now) {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    catchupMetrics.beginScan();
    List<BusinessCalendarEntity> calendars = businessCalendarMapper.selectByEnabled(true);
    if (calendars == null || calendars.isEmpty()) {
      catchupMetrics.completeScan();
      return;
    }
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    try {
      for (BusinessCalendarEntity calendar : calendars) {
        if (EmptyChecks.isNull(calendar) || !Texts.hasText(calendar.tenantId())) {
          // openOne 内部也校验,这里先过滤一道,避免空 tenant 触发 holder NPE 路径。
          continue;
        }
        // RLS Phase B：openOne 的所有 mapper（batch_day_instance / calendar_dependency /
        // disaster_day_override / job_execution_log）都依赖租户上下文；绑定要包住整个 tx，让 short tx
        // 的所有写入都看到 app.tenant_id。
        long calendarLagDays = RlsTenantContextHolder.runWithTenant(
            calendar.tenantId(),
            () ->
                // 每个 calendar 一笔 short tx：依赖等待、批次日实例和审计日志在同事务收敛。
                tx.execute(status -> openCalendarInTransaction(calendar, now)));
        catchupMetrics.recordCalendarLag(calendarLagDays);
      }
      catchupMetrics.completeScan();
    } catch (RuntimeException failure) {
      catchupMetrics.failScan();
      throw failure;
    }
  }

  private long openCalendarInTransaction(BusinessCalendarEntity calendar, Instant now) {
    catchupControlMapper.setTenantContext(calendar.tenantId());
    long lagDays = openMissingDays(calendar, now);
    reconcileDependencyWaits(calendar, now);
    return lagDays;
  }

  /**
   * 按持久化扫描游标补齐缺口。游标记录原始日历日（而非 holiday-roll 后的有效日），因此 NEXT_WORKDAY
   * 映射未来日期、依赖等待和灾难日延迟都不会让扫描停在同一个原始日期。每轮处理数量有上限。
   */
  private long openMissingDays(BusinessCalendarEntity calendar, Instant now) {
    ZoneId zoneId = timezoneProvider.resolveOrDefault(calendar.timezone());
    LocalTime cutoffTime =
        EmptyChecks.isNull(calendar.cutoffTime()) ? DEFAULT_CUTOFF_TIME : calendar.cutoffTime();
    ZonedDateTime localNow = now.atZone(zoneId);
    LocalDate currentBizDate = localNow.toLocalTime().isBefore(cutoffTime)
        ? localNow.toLocalDate().minusDays(1)
        : localNow.toLocalDate();
    catchupControlMapper.insertIfMissing(calendar.tenantId(), calendar.calendarCode());
    BatchDayCatchupControlEntity catchupControl =
        catchupControlMapper.selectForUpdate(calendar.tenantId(), calendar.calendarCode());
    if (EmptyChecks.isNotNull(catchupControl) && Boolean.TRUE.equals(catchupControl.paused())) {
      // 继续开启今天的业务日期，同时让较早积压日期和游标保持不变。
      // 保持不变。
      openOne(calendar, currentBizDate, now);
      LocalDate pausedCursor =
          batchDayInstanceMapper.selectOpenCursorDate(calendar.tenantId(), calendar.calendarCode());
      if (EmptyChecks.isNull(pausedCursor)) {
        pausedCursor = batchDayInstanceMapper.selectLatestBizDateAtOrBefore(
            calendar.tenantId(), calendar.calendarCode(), currentBizDate);
      }
      if (EmptyChecks.isNull(pausedCursor)) pausedCursor = currentBizDate.minusDays(1);
      return ChronoUnit.DAYS.between(pausedCursor, currentBizDate);
    }
    LocalDate latest = batchDayInstanceMapper.selectLatestBizDateAtOrBefore(
        calendar.tenantId(), calendar.calendarCode(), currentBizDate);
    LocalDate initialCursor = EmptyChecks.isNull(latest) ? currentBizDate.minusDays(1) : latest;
    batchDayInstanceMapper.initializeOpenCursor(
        calendar.tenantId(), calendar.calendarCode(), initialCursor);
    LocalDate cursor = batchDayInstanceMapper.selectOpenCursorForUpdate(
        calendar.tenantId(), calendar.calendarCode());
    if (EmptyChecks.isNull(cursor)) {
      throw new IllegalStateException("Batch-day open cursor could not be initialized for tenant="
          + calendar.tenantId() + ", calendar=" + calendar.calendarCode());
    }
    int limit = Math.max(1, batchDayScanProperties.getCatchupDaysPerScan());
    for (int scanned = 0; cursor.isBefore(currentBizDate) && scanned < limit; scanned++) {
      LocalDate next = cursor.plusDays(1);
      if (!openOne(calendar, next, now)) {
        // 终态日历决策前，请求已先取得共享围栏。将该日期保留在
        // 游标位置，待请求启动或进入终态后由后续扫描结算。
        break;
      }
      BatchDayInstanceAdvanceOpenCursorParam batchDayInstanceAdvanceOpenCursorParam =
          BatchDayInstanceAdvanceOpenCursorParam.builder()
              .tenantId(calendar.tenantId())
              .calendarCode(calendar.calendarCode())
              .expectedBizDate(cursor)
              .nextBizDate(next)
              .scannedAt(now)
              .build();
      if (batchDayInstanceMapper.advanceOpenCursor(batchDayInstanceAdvanceOpenCursorParam) != 1) {
        throw new IllegalStateException(
            "Batch-day open cursor changed while locked for tenant=" + calendar.tenantId()
                + ", calendar=" + calendar.calendarCode() + ", expected=" + cursor);
      }
      cursor = next;
    }
    return ChronoUnit.DAYS.between(cursor, currentBizDate);
  }

  void openOne(BusinessCalendarEntity calendar, Instant now) {
    if (calendar == null
        || !Texts.hasText(calendar.tenantId())
        || !Texts.hasText(calendar.calendarCode())) {
      return;
    }
    ZoneId zoneId = timezoneProvider.resolveOrDefault(calendar.timezone());
    LocalTime cutoffTime =
        calendar.cutoffTime() == null ? DEFAULT_CUTOFF_TIME : calendar.cutoffTime();
    ZonedDateTime localNow = now.atZone(zoneId);
    LocalDate rawBizDate = localNow.toLocalTime().isBefore(cutoffTime)
        ? localNow.toLocalDate().minusDays(1)
        : localNow.toLocalDate();
    openOne(calendar, rawBizDate, now);
  }

  private boolean openOne(BusinessCalendarEntity calendar, LocalDate rawBizDate, Instant now) {
    if (EmptyChecks.isNull(calendar) || EmptyChecks.isNull(rawBizDate)) return true;
    BatchDayInstanceEntity rawExisting = batchDayInstanceMapper.selectByTenantCalendarBizDate(
        calendar.tenantId(), calendar.calendarCode(), rawBizDate);
    if (EmptyChecks.isNotNull(rawExisting)) return true;
    // 灾难恢复覆盖规则必须优先于普通日历节假日规则。
    DisasterDayOverrideEntity rawDisaster = disasterDayOverrideMapper.selectActiveByCalendarBizDate(
        calendar.tenantId(), calendar.calendarCode(), rawBizDate, now);
    if (EmptyChecks.isNotNull(rawDisaster)) {
      return handleDisasterOverride(calendar, rawBizDate, rawDisaster, now);
    }
    LocalDate bizDate = resolveEffectiveBizDate(calendar, rawBizDate);
    // SKIP 是有效的日历结果。将其持久化为终态，避免当天一直保持
    // OPEN/CUTOFF，尤其是定时触发按预期跳过该节假日时。
    if (EmptyChecks.isNull(bizDate)) {
      return openSkippedHoliday(calendar, rawBizDate, now);
    }
    if (!rawBizDate.equals(bizDate)) {
      BatchDayInstanceEntity rolledExisting = batchDayInstanceMapper.selectByTenantCalendarBizDate(
          calendar.tenantId(), calendar.calendarCode(), bizDate);
      if (EmptyChecks.isNotNull(rolledExisting)) return true;
      DisasterDayOverrideEntity rolledDisaster =
          disasterDayOverrideMapper.selectActiveByCalendarBizDate(
              calendar.tenantId(), calendar.calendarCode(), bizDate, now);
      if (EmptyChecks.isNotNull(rolledDisaster)) {
        return handleDisasterOverride(calendar, bizDate, rolledDisaster, now);
      }
    }
    // ADR-023 Stage 3: 跨 calendar 串联 — 上游未达期望状态则推迟
    String dependencyBlockReason = checkCalendarDependencies(calendar, bizDate, now);
    if (dependencyBlockReason != null) {
      persistDependencyWait(calendar, bizDate, now, dependencyBlockReason);
      log.info(
          "batch day deferred by upstream calendar: tenantId={}, calendarCode={}, bizDate={},"
              + " reason={}",
          calendar.tenantId(),
          calendar.calendarCode(),
          bizDate,
          dependencyBlockReason);
      return true;
    }
    Instant cutoffAt = timePolicyResolver.resolveCutoffAt(calendar, bizDate);
    ZoneId zoneId = timezoneProvider.resolveOrDefault(calendar.timezone());
    Instant slaDeadlineAt = resolveSlaDeadlineAt(calendar, cutoffAt);
    String timezoneSnapshot = zoneId.getId();
    String dstPolicySnapshot = timePolicyResolver.snapshot(calendar);
    BatchDayTimingSnapshot timing =
        new BatchDayTimingSnapshot(cutoffAt, slaDeadlineAt, timezoneSnapshot, dstPolicySnapshot);
    BatchDayInstanceEntity.BatchDayInstanceEntityBuilder dayBuilder =
        newOpenBatchDay(calendar, bizDate, now, timing);
    BatchDayInstanceEntity openedDay = dayBuilder.build();
    persistOpenedBatchDay(openedDay);
    return true;
  }

  /** 应用与定时 Trigger 业务日期解析相同的节假日顺延策略。 */
  private LocalDate resolveEffectiveBizDate(BusinessCalendarEntity calendar, LocalDate rawBizDate) {
    String dayType = planCalendarMapper.selectEffectiveDayType(
        calendar.tenantId(), calendar.calendarCode(), rawBizDate);
    if (!"HOLIDAY".equalsIgnoreCase(dayType)) return rawBizDate;

    String configuredHolidayRollRule = calendar.holidayRollRule();
    String rule;
    if (EmptyChecks.isNull(configuredHolidayRollRule)) {
      rule = "SKIP";
    } else {
      String trimmedHolidayRollRule = configuredHolidayRollRule.trim();
      rule = trimmedHolidayRollRule.toUpperCase(Locale.ROOT);
    }
    if ("SKIP".equals(rule)) return null;
    int direction = "PREV_WORKDAY".equals(rule) ? -1 : 1;
    if (!"PREV_WORKDAY".equals(rule) && !"NEXT_WORKDAY".equals(rule)) return rawBizDate;

    LocalDate candidate = rawBizDate.plusDays(direction);
    for (int day = 0; day < 365; day++) {
      String candidateType = planCalendarMapper.selectEffectiveDayType(
          calendar.tenantId(), calendar.calendarCode(), candidate);
      if (!"HOLIDAY".equalsIgnoreCase(candidateType)) return candidate;
      candidate = candidate.plusDays(direction);
    }
    throw new IllegalStateException(
        "No workday found within 365 days for tenant=" + calendar.tenantId() + ", calendar="
            + calendar.calendarCode() + ", bizDate=" + rawBizDate);
  }

  private boolean openSkippedHoliday(
      BusinessCalendarEntity calendar, LocalDate bizDate, Instant now) {
    ZoneId zoneId = timezoneProvider.resolveOrDefault(calendar.timezone());
    BatchDayInstanceEntity.BatchDayInstanceEntityBuilder skippedBuilder =
        BatchDayInstanceEntity.builder()
            .tenantId(calendar.tenantId())
            .calendarCode(calendar.calendarCode())
            .bizDate(bizDate)
            .dayStatus("SKIPPED")
            .openAt(now);
    Instant cutoffAt = timePolicyResolver.resolveCutoffAt(calendar, bizDate);
    skippedBuilder.cutoffAt(cutoffAt);
    skippedBuilder.settledAt(now).lateCount(0).catchupCount(0);
    String timezoneSnapshot = zoneId.getId();
    skippedBuilder.timezoneSnapshot(timezoneSnapshot);
    String dstPolicySnapshot = timePolicyResolver.snapshot(calendar);
    BatchDayInstanceEntity skipped = skippedBuilder
        .dstPolicySnapshot(dstPolicySnapshot)
        .frozen(false)
        .operationReason("CALENDAR_HOLIDAY_SKIP")
        .operatedAt(now)
        .version(0L)
        .createdAt(now)
        .updatedAt(now)
        .dryRun(false)
        .build();
    return insertSkippedBatchDayIfNoPendingRequest(skipped, now);
  }

  /**
   * ADR-023 Stage 3 — 检查 downstream calendar 的所有 dependency。返回非 null 表示 upstream 未达期望状态，应推迟开 day；返回
   * null 表示 通过。
   */
  private String checkCalendarDependencies(
      BusinessCalendarEntity calendar, LocalDate bizDate, Instant now) {
    List<CalendarDependencyEntity> deps = calendarDependencyMapper.selectEnabledByDownstream(
        calendar.tenantId(), calendar.calendarCode());
    if (deps == null || deps.isEmpty()) {
      return null;
    }
    for (CalendarDependencyEntity dep : deps) {
      if (dep == null || !Boolean.TRUE.equals(dep.enabled())) continue;
      String rule = dep.rule() == null ? CalendarDependencyEntity.RULE_WAIT_SETTLED : dep.rule();
      if (CalendarDependencyEntity.RULE_SAME_DAY_PARALLEL.equals(rule)) {
        // v1 占位：不阻塞
        continue;
      }
      BatchDayInstanceEntity upstream = batchDayInstanceMapper.selectByTenantCalendarBizDate(
          calendar.tenantId(), dep.upstreamCode(), bizDate);
      if (upstream == null) {
        return "BLOCKED_BY_UPSTREAM_CALENDAR:upstream=" + dep.upstreamCode() + ":missing";
      }
      if (CalendarDependencyEntity.RULE_WAIT_SETTLED.equals(rule)
          && !"SETTLED".equalsIgnoreCase(upstream.dayStatus())) {
        return "BLOCKED_BY_UPSTREAM_CALENDAR:upstream="
            + dep.upstreamCode()
            + ":status="
            + upstream.dayStatus();
      }
      if (CalendarDependencyEntity.RULE_WAIT_CUTOFF.equals(rule)) {
        Instant upstreamCutoffAt = upstream.cutoffAt();
        boolean upstreamCutoffIsPending =
            EmptyChecks.isNull(upstreamCutoffAt) || now.isBefore(upstreamCutoffAt);
        if (upstreamCutoffIsPending) {
          return "BLOCKED_BY_UPSTREAM_CALENDAR:upstream=" + dep.upstreamCode() + ":cutoff_pending";
        }
      }
    }
    return null;
  }

  private void persistDependencyWait(
      BusinessCalendarEntity calendar, LocalDate bizDate, Instant now, String reason) {
    CalendarDependencyWaitEntity existing =
        calendarDependencyWaitMapper.selectByTenantCalendarBizDate(
            calendar.tenantId(), calendar.calendarCode(), bizDate);
    Instant cutoffAt = timePolicyResolver.resolveCutoffAt(calendar, bizDate);
    List<Map<String, String>> dependencySnapshot =
        calendarDependencyMapper
            .selectEnabledByDownstream(calendar.tenantId(), calendar.calendarCode())
            .stream()
            .filter(dep -> EmptyChecks.isNotNull(dep) && Boolean.TRUE.equals(dep.enabled()))
            .map(dep -> Map.of(
                "upstreamCode",
                dep.upstreamCode(),
                "rule",
                EmptyChecks.isNull(dep.rule())
                    ? CalendarDependencyEntity.RULE_WAIT_SETTLED
                    : dep.rule()))
            .toList();
    CalendarDependencyWaitEntity.CalendarDependencyWaitEntityBuilder waitingEntityBuilder =
        CalendarDependencyWaitEntity.builder()
            .tenantId(calendar.tenantId())
            .calendarCode(calendar.calendarCode())
            .bizDate(bizDate)
            .blockedReason(reason)
            .firstWaitingAt(now)
            .lastCheckedAt(now)
            .cutoffAt(cutoffAt);
    Instant slaDeadlineAt = resolveSlaDeadlineAt(calendar, cutoffAt);
    waitingEntityBuilder.slaDeadlineAt(slaDeadlineAt);
    ZoneId resolvedTimezone = timezoneProvider.resolveOrDefault(calendar.timezone());
    String timezoneSnapshot = resolvedTimezone.getId();
    waitingEntityBuilder.timezoneSnapshot(timezoneSnapshot);
    String dstPolicySnapshot = timePolicyResolver.snapshot(calendar);
    waitingEntityBuilder.dstPolicySnapshot(dstPolicySnapshot);
    String dependencySnapshotJson = JsonUtils.toJson(dependencySnapshot);
    waitingEntityBuilder.dependencySnapshot(dependencySnapshotJson);
    calendarDependencyWaitMapper.upsertWaiting(waitingEntityBuilder.build());
    maybeEmitDependencyWaitTimeout(calendar, existing, reason, now);
  }

  private void maybeEmitDependencyWaitTimeout(
      BusinessCalendarEntity calendar,
      CalendarDependencyWaitEntity wait,
      String reason,
      Instant now) {
    boolean dependencyWaitNeedsNoTimeoutAlert = EmptyChecks.isNull(wait)
        || EmptyChecks.isNotNull(wait.timeoutAlertedAt())
        || EmptyChecks.isNull(wait.firstWaitingAt());
    if (dependencyWaitNeedsNoTimeoutAlert) {
      return;
    }
    long configuredTimeoutSeconds = batchDayScanProperties.getDependencyWaitTimeoutSeconds();
    if (configuredTimeoutSeconds <= 0) {
      return;
    }
    long deadlineTimeoutSeconds = batchDayScanProperties.getDependencyWaitTimeoutSeconds();
    Instant timeoutAt = wait.firstWaitingAt().plusSeconds(deadlineTimeoutSeconds);
    if (now.isBefore(timeoutAt)) {
      return;
    }
    int claimed =
        calendarDependencyWaitMapper.claimTimeoutAlert(calendar.tenantId(), wait.id(), now);
    if (claimed > 0) emitDependencyWaitTimeout(calendar, wait, reason);
  }

  private void emitDependencyWaitTimeout(
      BusinessCalendarEntity calendar, CalendarDependencyWaitEntity wait, String reason) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("calendarCode", wait.calendarCode());
    detail.put("bizDate", wait.bizDate().toString());
    detail.put("blockedReason", reason);
    detail.put("firstWaitingAt", wait.firstWaitingAt().toString());
    detail.put("timeoutSeconds", batchDayScanProperties.getDependencyWaitTimeoutSeconds());
    AlertEmitRequest alertRequest = AlertEmitRequest.builder()
        .tenantId(calendar.tenantId())
        .serviceName("batch-orchestrator")
        .alertType("BATCH_CALENDAR_DEPENDENCY_WAIT_TIMEOUT")
        .severity("ERROR")
        .title("calendar dependency wait exceeded timeout")
        .detailJson(JsonUtils.toJson(detail))
        .resourceKey(calendar.tenantId() + ":" + wait.calendarCode() + ":" + wait.bizDate())
        .build();
    alertEventService.emit(alertRequest);
  }

  /** 在当前业务日期之后重新检查已持久化日期；截止时间到达时，存储日期不会漂移。 */
  private void reconcileDependencyWaits(BusinessCalendarEntity calendar, Instant now) {
    List<CalendarDependencyWaitEntity> pending =
        calendarDependencyWaitMapper.selectWaitingForUpdate(
            calendar.tenantId(), calendar.calendarCode(), now, 100);
    if (EmptyChecks.isEmpty(pending)) return;
    for (CalendarDependencyWaitEntity wait : pending) {
      BatchDayInstanceEntity existing = batchDayInstanceMapper.selectByTenantCalendarBizDate(
          wait.tenantId(), wait.calendarCode(), wait.bizDate());
      if (EmptyChecks.isNotNull(existing)) {
        markDependencyWait(wait, CalendarDependencyWaitEntity.STATUS_OPENED, now);
        continue;
      }
      DisasterDayOverrideEntity disaster = disasterDayOverrideMapper.selectActiveByCalendarBizDate(
          wait.tenantId(), wait.calendarCode(), wait.bizDate(), now);
      if (EmptyChecks.isNotNull(disaster)) {
        boolean handled = handleDisasterOverride(calendar, wait.bizDate(), disaster, now);
        if (handled && DisasterDayOverrideEntity.ACTION_SKIP.equals(disaster.action())) {
          markDependencyWait(wait, CalendarDependencyWaitEntity.STATUS_SKIPPED, now);
        } else {
          calendarDependencyWaitMapper.updateCheckResult(
              wait.tenantId(), wait.id(), "DISASTER_DAY_DEFERRED", now);
        }
        continue;
      }
      if (CalendarDependencyWaitEntity.MANUAL_ACTION_SKIP.equals(wait.manualAction())) {
        if (openManuallySkippedDay(calendar, wait, now)) {
          markDependencyWait(wait, CalendarDependencyWaitEntity.STATUS_SKIPPED, now);
        }
        continue;
      }
      if (CalendarDependencyWaitEntity.MANUAL_ACTION_RELEASE.equals(wait.manualAction())) {
        openWaitingBatchDay(calendar, wait, now);
        markDependencyWait(wait, CalendarDependencyWaitEntity.STATUS_OPENED, now);
        continue;
      }
      String blockedReason = checkCalendarDependencies(calendar, wait.bizDate(), now);
      if (EmptyChecks.isNotNull(blockedReason)) {
        calendarDependencyWaitMapper.updateCheckResult(
            wait.tenantId(), wait.id(), blockedReason, now);
        maybeEmitDependencyWaitTimeout(calendar, wait, blockedReason, now);
        continue;
      }
      openWaitingBatchDay(calendar, wait, now);
      markDependencyWait(wait, CalendarDependencyWaitEntity.STATUS_OPENED, now);
    }
  }

  private void markDependencyWait(CalendarDependencyWaitEntity wait, String status, Instant now) {
    calendarDependencyWaitMapper.updateStatus(wait.tenantId(), wait.id(), status, now);
  }

  /** 恢复只使用首次等待时固定的时间策略，不能按当前日历重算旧业务日。 */
  private void openWaitingBatchDay(
      BusinessCalendarEntity calendar, CalendarDependencyWaitEntity wait, Instant now) {
    BatchDayTimingSnapshot timing = new BatchDayTimingSnapshot(
        wait.cutoffAt(), wait.slaDeadlineAt(), wait.timezoneSnapshot(), wait.dstPolicySnapshot());
    BatchDayInstanceEntity.BatchDayInstanceEntityBuilder dayBuilder =
        newOpenBatchDay(calendar, wait.bizDate(), now, timing);
    if (CalendarDependencyWaitEntity.MANUAL_ACTION_RELEASE.equals(wait.manualAction())) {
      dayBuilder.operatedBy(wait.manualOperatorId());
      dayBuilder.operationReason("MANUAL_CALENDAR_DEPENDENCY_RELEASE:" + wait.manualReason());
      dayBuilder.operatedAt(EmptyChecks.isNull(wait.manualOperatorId()) ? null : now);
    }
    BatchDayInstanceEntity openedDay = dayBuilder.build();
    persistOpenedBatchDay(openedDay);
  }

  /** 四个时间策略字段必须来自同一次解析或同一条持久化等待记录。 */
  private record BatchDayTimingSnapshot(
      Instant cutoffAt, Instant slaDeadlineAt, String timezone, String dstPolicy) {}

  private BatchDayInstanceEntity.BatchDayInstanceEntityBuilder newOpenBatchDay(
      BusinessCalendarEntity calendar,
      LocalDate bizDate,
      Instant now,
      BatchDayTimingSnapshot timing) {
    BatchDayInstanceEntity.BatchDayInstanceEntityBuilder dayBuilder =
        BatchDayInstanceEntity.builder()
            .tenantId(calendar.tenantId())
            .calendarCode(calendar.calendarCode())
            .bizDate(bizDate)
            .dayStatus("OPEN")
            .openAt(now)
            .cutoffAt(timing.cutoffAt())
            .slaDeadlineAt(timing.slaDeadlineAt())
            .lateCount(0)
            .catchupCount(0)
            .timezoneSnapshot(timing.timezone())
            .dstPolicySnapshot(timing.dstPolicy())
            .frozen(false)
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .dryRun(false);
    return dayBuilder;
  }

  private void persistOpenedBatchDay(BatchDayInstanceEntity openedDay) {
    String tenantId = openedDay.tenantId();
    String calendarCode = openedDay.calendarCode();
    LocalDate bizDate = openedDay.bizDate();
    String timezoneSnapshot = openedDay.timezoneSnapshot();
    // 创建业务日记录或其义务前，先获取与结算相同的事务围栏，
    // 使开日和最终结算遵循同一加锁顺序。
    batchDayInstanceMapper.acquireSettlementFence(tenantId, calendarCode, bizDate);
    if (batchDayInstanceMapper.insert(openedDay) > 0) {
      fileGroupExpectationService.materializeForOpenedBusinessDay(
          tenantId, calendarCode, bizDate, timezoneSnapshot);
      externalEventExpectationService.materializeForOpenedBusinessDay(
          tenantId, calendarCode, bizDate, timezoneSnapshot);
      appendAuditLog(openedDay, openedDay.openAt());
      log.info(
          "batch day opened: tenantId={}, calendarCode={}, bizDate={}, cutoffAt={}",
          tenantId,
          calendarCode,
          bizDate,
          openedDay.cutoffAt());
    }
  }

  private boolean openManuallySkippedDay(
      BusinessCalendarEntity calendar, CalendarDependencyWaitEntity wait, Instant now) {
    BatchDayInstanceEntity skipped = BatchDayInstanceEntity.builder()
        .tenantId(calendar.tenantId())
        .calendarCode(calendar.calendarCode())
        .bizDate(wait.bizDate())
        .dayStatus("SKIPPED")
        .openAt(now)
        .cutoffAt(wait.cutoffAt())
        .slaDeadlineAt(wait.slaDeadlineAt())
        .settledAt(now)
        .lateCount(0)
        .catchupCount(0)
        .timezoneSnapshot(wait.timezoneSnapshot())
        .dstPolicySnapshot(wait.dstPolicySnapshot())
        .frozen(false)
        .operationReason("MANUAL_CALENDAR_DEPENDENCY_SKIP:" + wait.manualReason())
        .operatedBy(wait.manualOperatorId())
        .operatedAt(now)
        .version(0L)
        .createdAt(now)
        .updatedAt(now)
        .dryRun(false)
        .build();
    return insertSkippedBatchDayIfNoPendingRequest(skipped, now);
  }

  /**
   * ADR-023 Stage 4 — 处理灾难日 override：SKIP 直接落 SKIPPED 状态行；DEFER_TO_NEXT_BIZDAY 仅记日志（依赖次日自然推进，TTL
   * 期内不会重开同 bizDate）。
   */
  private boolean handleDisasterOverride(
      BusinessCalendarEntity calendar,
      LocalDate bizDate,
      DisasterDayOverrideEntity disaster,
      Instant now) {
    if (DisasterDayOverrideEntity.ACTION_SKIP.equals(disaster.action())) {
      Instant cutoffAt = timePolicyResolver.resolveCutoffAt(calendar, bizDate);
      BatchDayInstanceEntity.BatchDayInstanceEntityBuilder skippedBuilder =
          BatchDayInstanceEntity.builder()
              .tenantId(calendar.tenantId())
              .calendarCode(calendar.calendarCode())
              .bizDate(bizDate)
              .dayStatus("SKIPPED")
              .openAt(now)
              .cutoffAt(cutoffAt)
              .settledAt(now)
              .lateCount(0)
              .catchupCount(0);
      ZoneId resolvedTimezone = timezoneProvider.resolveOrDefault(calendar.timezone());
      String timezoneSnapshot = resolvedTimezone.getId();
      skippedBuilder.timezoneSnapshot(timezoneSnapshot);
      String dstPolicySnapshot = timePolicyResolver.snapshot(calendar);
      BatchDayInstanceEntity skipped = skippedBuilder
          .dstPolicySnapshot(dstPolicySnapshot)
          .frozen(false)
          .operationReason("DISASTER_DAY_SKIP:" + disaster.reason())
          .operatedBy(disaster.approvedBy())
          .operatedAt(now)
          .version(0L)
          .createdAt(now)
          .updatedAt(now)
          .dryRun(false)
          .build();
      return insertSkippedBatchDayIfNoPendingRequest(skipped, now);
    }
    // DEFER_TO_NEXT_BIZDAY：本轮不开，等次日 scheduler 自然推进；只记日志便于排障。
    log.info(
        "batch day disaster DEFER: tenantId={}, calendarCode={}, bizDate={}, ttlUntil={},"
            + " approvedBy={}",
        calendar.tenantId(),
        calendar.calendarCode(),
        bizDate,
        disaster.ttlUntil(),
        disaster.approvedBy());
    return true;
  }

  /**
   * 将终态日历跳过与 Trigger 准入串行化，并保留已
   * 取得围栏的请求。请求启动或进入终态前，游标继续停留在该日期。
   */
  private boolean insertSkippedBatchDayIfNoPendingRequest(
      BatchDayInstanceEntity skipped, Instant now) {
    batchDayInstanceMapper.acquireSettlementFence(
        skipped.tenantId(), skipped.calendarCode(), skipped.bizDate());
    BatchDayInstanceEntity existingBatchDay = batchDayInstanceMapper.selectByTenantCalendarBizDate(
        skipped.tenantId(), skipped.calendarCode(), skipped.bizDate());
    if (EmptyChecks.isNotNull(existingBatchDay)) {
      return true;
    }
    int unlaunched = triggerRequestMapper.countUnlaunchedForCalendarBizDate(
        skipped.tenantId(), skipped.calendarCode(), skipped.bizDate(), UNLAUNCHED_REQUEST_STATUSES);
    if (unlaunched > 0) {
      log.info(
          "batch day skip deferred for accepted trigger request: tenantId={}, calendarCode={},"
              + " bizDate={}, unlaunchedRequests={}",
          skipped.tenantId(),
          skipped.calendarCode(),
          skipped.bizDate(),
          unlaunched);
      return false;
    }
    if (batchDayInstanceMapper.insert(skipped) > 0) {
      appendAuditLog(skipped, now);
      if ("CALENDAR_HOLIDAY_SKIP".equals(skipped.operationReason())) {
        log.info(
            "batch day skipped by holiday calendar: tenantId={}, calendarCode={}, bizDate={}",
            skipped.tenantId(),
            skipped.calendarCode(),
            skipped.bizDate());
      } else {
        log.warn(
            "batch day skipped by terminal calendar decision: tenantId={}, calendarCode={},"
                + " bizDate={}, reason={}",
            skipped.tenantId(),
            skipped.calendarCode(),
            skipped.bizDate(),
            skipped.operationReason());
      }
    }
    return true;
  }

  private Instant resolveSlaDeadlineAt(BusinessCalendarEntity calendar, Instant cutoffAt) {
    Integer slaOffsetMinutes = calendar.slaOffsetMin();
    if (EmptyChecks.isNull(slaOffsetMinutes) || slaOffsetMinutes <= 0) {
      return null;
    }
    long slaOffsetSeconds = slaOffsetMinutes * 60L;
    return cutoffAt.plusSeconds(slaOffsetSeconds);
  }

  private void appendAuditLog(BatchDayInstanceEntity opened, Instant now) {
    JobExecutionLogEntity logEntity = new JobExecutionLogEntity();
    logEntity.setTenantId(opened.tenantId());
    logEntity.setJobInstanceId(null);
    logEntity.setJobPartitionId(null);
    logEntity.setLogLevel("INFO");
    logEntity.setLogType(AuditLogConstants.LOG_TYPE_AUDIT);
    logEntity.setTraceId(null);
    logEntity.setMessage("BATCH_DAY_INSTANCE_STATUS_CHANGED");
    logEntity.setDetailRef(AuditLogConstants.DETAIL_REF_BATCH_DAY_INSTANCE);
    LinkedHashMap<String, Object> extra = new LinkedHashMap<>();
    extra.put("calendarCode", opened.calendarCode());
    extra.put("bizDate", opened.bizDate() == null ? null : opened.bizDate().toString());
    extra.put("fromDayStatus", null);
    extra.put("toDayStatus", opened.dayStatus());
    extra.put(
        "reasonCode",
        EmptyChecks.isNull(opened.operationReason())
            ? "BATCH_DAY_OPENED_BY_SCHEDULER"
            : opened.operationReason());
    extra.put(
        "operatorId",
        EmptyChecks.isNull(opened.operatedBy())
            ? AuditLogConstants.OPERATOR_ID_SYSTEM_BATCH_DAY_OPEN
            : opened.operatedBy());
    extra.put(
        "operatorType",
        EmptyChecks.isNull(opened.operatedBy()) ? AuditLogConstants.OPERATOR_TYPE_SYSTEM : "USER");
    extra.put("cutoffAt", opened.cutoffAt() == null ? null : opened.cutoffAt().toString());
    extra.put(
        "slaDeadlineAt",
        opened.slaDeadlineAt() == null ? null : opened.slaDeadlineAt().toString());
    extra.put("timezoneSnapshot", opened.timezoneSnapshot());
    extra.put("at", now.toString());
    String extraJson = JsonUtils.toJson(extra);
    logEntity.setExtraJson(extraJson);
    jobExecutionLogMapper.insert(logEntity);
  }
}
