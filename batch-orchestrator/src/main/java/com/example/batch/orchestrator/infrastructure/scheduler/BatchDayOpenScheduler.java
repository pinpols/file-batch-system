package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.logging.AuditLogConstants;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.BatchDayInstanceMapper;
import com.example.batch.orchestrator.mapper.BusinessCalendarMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.service.BatchDayTimePolicyResolver;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * 主动打开批次日实例。
 *
 * <p>触发链路仍保留 {@code LaunchBatchDayService} 的 upsert 兜底；本调度器负责让日切后的 {@code batch_day_instance}
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

  @Transactional
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
    for (BusinessCalendarEntity calendar : calendars) {
      openOne(calendar, now);
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
