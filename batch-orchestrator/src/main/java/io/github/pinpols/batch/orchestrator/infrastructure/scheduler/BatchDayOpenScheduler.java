package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.CalendarDependencyEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.DisasterDayOverrideEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.CalendarDependencyMapper;
import io.github.pinpols.batch.orchestrator.mapper.DisasterDayOverrideMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.service.BatchDayTimePolicyResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

  private final BusinessCalendarMapper businessCalendarMapper;
  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final BatchTimezoneProvider timezoneProvider;
  private final BatchDayTimePolicyResolver timePolicyResolver;
  private final BatchDateTimeSupport dateTimeSupport;
  // ADR-023 Stage 3: 跨 calendar 串联依赖检查
  private final CalendarDependencyMapper calendarDependencyMapper;
  // ADR-023 Stage 4: 灾难日热切换检查
  private final DisasterDayOverrideMapper disasterDayOverrideMapper;
  // 事务边界：扫描循环本身不开事务（避免长事务 + 与 @SchedulerLock AOP 顺序歧义），
  // 每个 calendar 命中后通过 TransactionTemplate 单条 short tx 写入 batch_day_instance + 审计日志。
  private final PlatformTransactionManager transactionManager;

  @Scheduled(fixedDelayString = "${batch.batch-day.open-scan-interval-millis:60000}")
  @SchedulerLock(name = "batch_day_open", lockAtMostFor = "PT2M", lockAtLeastFor = "PT15S")
  public void scheduledOpen() {
    openDueBatchDays(dateTimeSupport.nowInstant());
  }

  public void openDueBatchDays(Instant now) {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    List<BusinessCalendarEntity> calendars = businessCalendarMapper.selectByEnabled(true);
    if (calendars == null || calendars.isEmpty()) {
      return;
    }
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    for (BusinessCalendarEntity calendar : calendars) {
      if (calendar == null || !Texts.hasText(calendar.tenantId())) {
        // openOne 内部也校验,这里先过滤一道,避免空 tenant 触发 holder NPE 路径。
        continue;
      }
      // RLS Phase B：openOne 的所有 mapper（batch_day_instance / calendar_dependency /
      // disaster_day_override / job_execution_log）都依赖租户上下文；绑定要包住整个 tx，让 short tx
      // 的所有写入都看到 app.tenant_id。
      RlsTenantContextHolder.runWithTenant(
          calendar.tenantId(),
          () ->
              // 每个 calendar 一笔 short tx：insert batch_day_instance + 审计日志同事务原子。
              tx.executeWithoutResult(status -> openOne(calendar, now)));
    }
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
    LocalDate bizDate =
        localNow.toLocalTime().isBefore(cutoffTime)
            ? localNow.toLocalDate().minusDays(1)
            : localNow.toLocalDate();
    BatchDayInstanceEntity existing =
        batchDayInstanceMapper.selectByTenantCalendarBizDate(
            calendar.tenantId(), calendar.calendarCode(), bizDate);
    if (existing != null) {
      return;
    }
    // ADR-023 Stage 4: disaster_day_override 优先级最高 — 命中即按 action 处理后退出
    DisasterDayOverrideEntity disaster =
        disasterDayOverrideMapper.selectActiveByCalendarBizDate(
            calendar.tenantId(), calendar.calendarCode(), bizDate, now);
    if (disaster != null) {
      handleDisasterOverride(calendar, bizDate, disaster, now);
      return;
    }
    // ADR-023 Stage 3: 跨 calendar 串联 — 上游未达期望状态则推迟
    String dependencyBlockReason = checkCalendarDependencies(calendar, bizDate);
    if (dependencyBlockReason != null) {
      log.info(
          "batch day deferred by upstream calendar: tenantId={}, calendarCode={}, bizDate={},"
              + " reason={}",
          calendar.tenantId(),
          calendar.calendarCode(),
          bizDate,
          dependencyBlockReason);
      return;
    }
    Instant cutoffAt = timePolicyResolver.resolveCutoffAt(calendar, bizDate);
    Instant slaDeadlineAt = resolveSlaDeadlineAt(calendar, cutoffAt);
    BatchDayInstanceEntity toInsert =
        BatchDayInstanceEntity.builder()
            .tenantId(calendar.tenantId())
            .calendarCode(calendar.calendarCode())
            .bizDate(bizDate)
            .dayStatus("OPEN")
            .openAt(now)
            .cutoffAt(cutoffAt)
            .slaDeadlineAt(slaDeadlineAt)
            .lateCount(0)
            .catchupCount(0)
            .timezoneSnapshot(zoneId.getId())
            .dstPolicySnapshot(timePolicyResolver.snapshot(calendar))
            .frozen(false)
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    int rows = batchDayInstanceMapper.insert(toInsert);
    if (rows <= 0) {
      return;
    }
    appendAuditLog(toInsert, now);
    log.info(
        "batch day opened: tenantId={}, calendarCode={}, bizDate={}, cutoffAt={}",
        calendar.tenantId(),
        calendar.calendarCode(),
        bizDate,
        cutoffAt);
  }

  /**
   * ADR-023 Stage 3 — 检查 downstream calendar 的所有 dependency。返回非 null 表示 upstream 未达期望状态，应推迟开 day；返回
   * null 表示 通过。
   */
  private String checkCalendarDependencies(BusinessCalendarEntity calendar, LocalDate bizDate) {
    List<CalendarDependencyEntity> deps =
        calendarDependencyMapper.selectEnabledByDownstream(
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
      BatchDayInstanceEntity upstream =
          batchDayInstanceMapper.selectByTenantCalendarBizDate(
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
        if (upstream.cutoffAt() == null
            || dateTimeSupport.nowInstant().isBefore(upstream.cutoffAt())) {
          return "BLOCKED_BY_UPSTREAM_CALENDAR:upstream=" + dep.upstreamCode() + ":cutoff_pending";
        }
      }
    }
    return null;
  }

  /**
   * ADR-023 Stage 4 — 处理灾难日 override：SKIP 直接落 SKIPPED 状态行；DEFER_TO_NEXT_BIZDAY 仅记日志（依赖次日自然推进，TTL
   * 期内不会重开同 bizDate）。
   */
  private void handleDisasterOverride(
      BusinessCalendarEntity calendar,
      LocalDate bizDate,
      DisasterDayOverrideEntity disaster,
      Instant now) {
    if (DisasterDayOverrideEntity.ACTION_SKIP.equals(disaster.action())) {
      Instant cutoffAt = timePolicyResolver.resolveCutoffAt(calendar, bizDate);
      BatchDayInstanceEntity skipped =
          BatchDayInstanceEntity.builder()
              .tenantId(calendar.tenantId())
              .calendarCode(calendar.calendarCode())
              .bizDate(bizDate)
              .dayStatus("SKIPPED")
              .openAt(now)
              .cutoffAt(cutoffAt)
              .settledAt(now)
              .lateCount(0)
              .catchupCount(0)
              .timezoneSnapshot(timezoneProvider.resolveOrDefault(calendar.timezone()).getId())
              .dstPolicySnapshot(timePolicyResolver.snapshot(calendar))
              .frozen(false)
              .operationReason("DISASTER_DAY_SKIP:" + disaster.reason())
              .operatedBy(disaster.approvedBy())
              .operatedAt(now)
              .version(0L)
              .createdAt(now)
              .updatedAt(now)
              .build();
      int rows = batchDayInstanceMapper.insert(skipped);
      if (rows > 0) {
        appendAuditLog(skipped, now);
        log.warn(
            "batch day disaster SKIP: tenantId={}, calendarCode={}, bizDate={}, approvedBy={},"
                + " reason={}",
            calendar.tenantId(),
            calendar.calendarCode(),
            bizDate,
            disaster.approvedBy(),
            disaster.reason());
      }
      return;
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
  }

  private Instant resolveSlaDeadlineAt(BusinessCalendarEntity calendar, Instant cutoffAt) {
    if (calendar.slaOffsetMin() == null || calendar.slaOffsetMin() <= 0) {
      return null;
    }
    return cutoffAt.plusSeconds(calendar.slaOffsetMin() * 60L);
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
    extra.put("reasonCode", "BATCH_DAY_OPENED_BY_SCHEDULER");
    extra.put("operatorId", AuditLogConstants.OPERATOR_ID_SYSTEM_BATCH_DAY_OPEN);
    extra.put("operatorType", AuditLogConstants.OPERATOR_TYPE_SYSTEM);
    extra.put("cutoffAt", opened.cutoffAt() == null ? null : opened.cutoffAt().toString());
    extra.put(
        "slaDeadlineAt", opened.slaDeadlineAt() == null ? null : opened.slaDeadlineAt().toString());
    extra.put("timezoneSnapshot", opened.timezoneSnapshot());
    extra.put("at", now.toString());
    logEntity.setExtraJson(JsonUtils.toJson(extra));
    jobExecutionLogMapper.insert(logEntity);
  }
}
