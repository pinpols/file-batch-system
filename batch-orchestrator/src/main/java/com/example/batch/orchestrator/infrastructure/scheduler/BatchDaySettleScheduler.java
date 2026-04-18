package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.logging.AuditLogConstants;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceRecord;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarRecord;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.query.BatchDayInstanceMetrics;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.repository.BatchDayInstanceRepository;
import com.example.batch.orchestrator.service.LaunchService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批次日结算调度器。
 *
 * <p>默认每 60 秒扫描一次处于 {@code CUTOFF} 或 {@code IN_FLIGHT} 状态的批次日实例，
 * 根据关联任务实例的运行/失败/全量计数决定将批次日推进至 {@code IN_FLIGHT}、{@code FAILED}
 * 或 {@code SETTLED}，并在结算为 FAILED 时按业务日历的追赶策略（AUTO/MANUAL_APPROVAL）
 * 自动发起补跑请求。ShedLock 锁名 {@code batch_day_settle}，最长持锁 3 分钟，最短持锁 30 秒。
 * 优雅停机（draining）期间直接跳过执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchDaySettleScheduler {

  private static final List<String> TRACKED_STATUSES = List.of("CUTOFF", "IN_FLIGHT");

  private final BatchDayInstanceRepository batchDayInstanceRepository;
  private final JobInstanceMapper jobInstanceMapper;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final TriggerRequestMapper triggerRequestMapper;
  private final OrchestratorConfigCacheService configCacheService;
  private final LaunchService launchService;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.batch-day.settle-scan-interval-millis:60000}")
  @SchedulerLock(name = "batch_day_settle", lockAtMostFor = "PT3M", lockAtLeastFor = "PT30S")
  public void scheduledSettle() {
    settle();
  }

  @Transactional
  public void settle() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    List<BatchDayInstanceRecord> candidates =
        batchDayInstanceRepository.findByDayStatusIn(TRACKED_STATUSES);
    if (candidates == null || candidates.isEmpty()) {
      return;
    }
    Instant now = Instant.now();
    for (BatchDayInstanceRecord candidate : candidates) {
      if (candidate == null || candidate.id() == null) {
        continue;
      }
      BatchDayInstanceMetrics metrics =
          jobInstanceMapper.selectBatchDayMetrics(
              candidate.tenantId(), candidate.calendarCode(), candidate.bizDate());
      if (metrics == null) {
        continue;
      }
      long activeCount = value(metrics.getActiveCount());
      long failedCount = value(metrics.getFailedCount());
      long totalCount = value(metrics.getTotalCount());
      if (activeCount > 0) {
        if (!"IN_FLIGHT".equals(candidate.dayStatus())) {
          BatchDayInstanceRecord from = candidate;
          BatchDayInstanceRecord to = candidate.withDayStatus("IN_FLIGHT", now);
          batchDayInstanceRepository.save(to);
          appendBatchDayAuditLog(from, to, "IN_FLIGHT_BECAUSE_ACTIVE_INSTANCES");
        }
        continue;
      }
      if (totalCount <= 0L) {
        continue;
      }
      if (failedCount > 0L) {
        BatchDayInstanceRecord from = candidate;
        BatchDayInstanceRecord to = candidate.withSettled("FAILED", now, now);
        batchDayInstanceRepository.save(to);
        appendBatchDayAuditLog(from, to, "BATCH_DAY_FAILED");
        driveCatchUp(candidate, now);
        log.info(
            "batch day settled as FAILED: tenantId={}, calendarCode={}, bizDate={}",
            candidate.tenantId(),
            candidate.calendarCode(),
            candidate.bizDate());
        continue;
      }
      BatchDayInstanceRecord from = candidate;
      BatchDayInstanceRecord to = candidate.withSettled("SETTLED", now, now);
      batchDayInstanceRepository.save(to);
      appendBatchDayAuditLog(from, to, "BATCH_DAY_SETTLED");
      log.info(
          "batch day settled as SETTLED: tenantId={}, calendarCode={}, bizDate={}",
          candidate.tenantId(),
          candidate.calendarCode(),
          candidate.bizDate());
    }
  }

  private long value(Long value) {
    return value == null ? 0L : value;
  }

  private void driveCatchUp(BatchDayInstanceRecord batchDay, Instant now) {
    BusinessCalendarRecord calendar =
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
            new LaunchRequest(
                request.getTenantId(),
                request.getJobCode(),
                request.getBizDate(),
                TriggerType.CATCH_UP,
                request.getRequestId(),
                request.getTraceId(),
                buildCatchUpParams(batchDay, candidate, calendar, now));
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
      BatchDayInstanceRecord from, BatchDayInstanceRecord to, String reasonCode) {
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
      BatchDayInstanceRecord batchDay, JobInstanceEntity candidate) {
    return batchDay.tenantId()
        + ":batch-day-catchup:"
        + batchDay.calendarCode()
        + ":"
        + batchDay.bizDate()
        + ":"
        + candidate.getJobCode();
  }

  private Map<String, Object> buildCatchUpParams(
      BatchDayInstanceRecord batchDay,
      JobInstanceEntity candidate,
      BusinessCalendarRecord calendar,
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
