package com.example.batch.trigger.infrastructure.scheduler;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.trigger.infrastructure.TriggerGracefulShutdown;
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

/**
 * 批处理日切（cutoff）扫描器：定时检查状态为 OPEN 的 {@code batch_day_instance}， 当当前本地时间超过租户配置的 cutoffTime 后将其标记为已切日。
 *
 * <p>默认 cutoffTime 为 06:00；每条候选记录带自己的时区，确保多地域租户的切日时间各自独立。 ShedLock 防止多节点重复处理同一批次；每条记录的 {@code
 * markCutoff} 用 CAS 更新， 即使并发也只有一次会成功（避免重复切日）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchDayCutoffScheduler {

  private static final LocalTime DEFAULT_CUTOFF_TIME = LocalTime.of(6, 0);

  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final TriggerGracefulShutdown gracefulShutdown;
  private final BatchTimezoneProvider timezoneProvider;
  private final BatchDateTimeSupport dateTimeSupport;

  @Transactional
  @Scheduled(fixedDelayString = "${batch.batch-day.cutoff-scan-interval-millis:60000}")
  @SchedulerLock(name = "batch_day_cutoff", lockAtMostFor = "PT2M", lockAtLeastFor = "PT15S")
  public void scheduledCutoff() {
    cutoff();
  }

  public void cutoff() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    List<BatchDayCutoffCandidate> candidates = batchDayInstanceMapper.selectOpenCutoffCandidates();
    if (candidates == null || candidates.isEmpty()) {
      return;
    }
    Instant now = dateTimeSupport.nowInstant();
    for (BatchDayCutoffCandidate candidate : candidates) {
      if (candidate == null || candidate.getId() == null) {
        continue;
      }
      LocalTime cutoffTime =
          candidate.getCutoffTime() == null ? DEFAULT_CUTOFF_TIME : candidate.getCutoffTime();
      ZoneId zoneId = timezoneProvider.resolveOrDefault(candidate.getTimezone());
      LocalTime localTime = now.atZone(zoneId).toLocalTime();
      if (localTime.isBefore(cutoffTime)) {
        continue;
      }
      int updated =
          batchDayInstanceMapper.markCutoff(
              candidate.getId(),
              candidate.getTenantId(),
              candidate.getCalendarCode(),
              candidate.getBizDate(),
              now);
      if (updated > 0) {
        log.info(
            "batch day cutoff applied: tenantId={}, calendarCode={}, bizDate={}," + " cutoffAt={}",
            candidate.getTenantId(),
            candidate.getCalendarCode(),
            candidate.getBizDate(),
            now);
      }
    }
  }
}
