package com.example.batch.trigger.infrastructure.scheduler;

import com.example.batch.trigger.mapper.BatchDayInstanceMapper;
import com.example.batch.trigger.support.BatchDayCutoffCandidate;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchDayCutoffScheduler {

    private static final LocalTime DEFAULT_CUTOFF_TIME = LocalTime.of(6, 0);

    private final BatchDayInstanceMapper batchDayInstanceMapper;

    @Scheduled(fixedDelayString = "${batch.batch-day.cutoff-scan-interval-millis:60000}")
    @SchedulerLock(name = "batch_day_cutoff", lockAtMostFor = "PT2M", lockAtLeastFor = "PT15S")
    public void scheduledCutoff() {
        cutoff();
    }

    @Transactional
    public void cutoff() {
        List<BatchDayCutoffCandidate> candidates = batchDayInstanceMapper.selectOpenCutoffCandidates();
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (BatchDayCutoffCandidate candidate : candidates) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            LocalTime cutoffTime = candidate.getCutoffTime() == null ? DEFAULT_CUTOFF_TIME : candidate.getCutoffTime();
            ZoneId zoneId = resolveZoneId(candidate.getTimezone());
            LocalTime localTime = now.atZone(zoneId).toLocalTime();
            if (localTime.isBefore(cutoffTime)) {
                continue;
            }
            int updated = batchDayInstanceMapper.markCutoff(
                    candidate.getId(),
                    candidate.getTenantId(),
                    candidate.getCalendarCode(),
                    candidate.getBizDate(),
                    now
            );
            if (updated > 0) {
                log.info("batch day cutoff applied: tenantId={}, calendarCode={}, bizDate={}, cutoffAt={}",
                        candidate.getTenantId(), candidate.getCalendarCode(), candidate.getBizDate(), now);
            }
        }
    }

    private ZoneId resolveZoneId(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.systemDefault();
        }
        return ZoneId.of(timezone);
    }
}
