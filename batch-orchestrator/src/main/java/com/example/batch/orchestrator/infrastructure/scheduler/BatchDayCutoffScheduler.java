package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.orchestrator.domain.entity.BatchDayInstanceRecord;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarRecord;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.common.logging.AuditLogConstants;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.repository.BatchDayInstanceRepository;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.example.batch.common.utils.JsonUtils;

/**
 * batch_day_instance 自动切换：OPEN -> CUTOFF（在 cutoff_time 之后）。
 *
 * <p>该状态机缺失会导致 late arrival 路由永远无法生效，因此必须补齐。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchDayCutoffScheduler {

    private final BatchDayInstanceRepository batchDayInstanceRepository;
    private final OrchestratorConfigCacheService configCacheService;
    private final JobExecutionLogMapper jobExecutionLogMapper;
    private final OrchestratorGracefulShutdown gracefulShutdown;

    @Scheduled(fixedDelayString = "${batch.batch-day.cutoff-scan-interval-millis:60000}")
    @SchedulerLock(name = "batch_day_cutoff", lockAtMostFor = "PT2M", lockAtLeastFor = "PT20S")
    public void scheduledAdvance() {
        advance();
    }

    @Transactional
    public void advance() {
        if (gracefulShutdown.isDraining()) {
            return;
        }
        Instant now = Instant.now();
        List<String> tracked = List.of("OPEN");

        List<BatchDayInstanceRecord> candidates = batchDayInstanceRepository.findByDayStatusIn(tracked);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        for (BatchDayInstanceRecord candidate : candidates) {
            if (candidate == null || candidate.tenantId() == null || candidate.calendarCode() == null || candidate.bizDate() == null) {
                continue;
            }
            Instant cutoffAt = candidate.cutoffAt();
            if (cutoffAt == null) {
                cutoffAt = resolveCutoffAt(candidate.tenantId(), candidate.calendarCode(), candidate.bizDate());
            }
            if (cutoffAt == null) {
                continue;
            }
            if (!now.isBefore(cutoffAt)) {
                BatchDayInstanceRecord updated = candidate.withCutoff(cutoffAt, now);
                batchDayInstanceRepository.save(updated);
                appendAuditLog(candidate, updated, "CUTOFF_REACHED", now);
                log.info("batch day advanced to CUTOFF: tenantId={}, calendarCode={}, bizDate={}",
                        candidate.tenantId(), candidate.calendarCode(), candidate.bizDate());
            }
        }
    }

    private Instant resolveCutoffAt(String tenantId, String calendarCode, java.time.LocalDate bizDate) {
        BusinessCalendarRecord calendar = configCacheService.findEnabledBusinessCalendar(tenantId, calendarCode);
        if (calendar == null) {
            return null;
        }
        LocalTime cutoffTime = calendar.cutoffTime() == null ? LocalTime.of(6, 0) : calendar.cutoffTime();
        ZoneId zoneId = StringUtils.hasText(calendar.timezone())
                ? ZoneId.of(calendar.timezone())
                : ZoneId.systemDefault();
        return bizDate.plusDays(1).atTime(cutoffTime).atZone(zoneId).toInstant();
    }

    private void appendAuditLog(BatchDayInstanceRecord from,
                                 BatchDayInstanceRecord to,
                                 String reasonCode,
                                 Instant now) {
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
