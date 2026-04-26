package com.example.batch.trigger.wheel;

import com.example.batch.trigger.mapper.TriggerMisfirePendingMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 周期把 PENDING 但已过期(默认 7 天)的 trigger_misfire_pending 改为 EXPIRED。 仅 wheel 模式启用。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "batch.trigger.scheduler-impl", havingValue = "wheel")
@RequiredArgsConstructor
public class MisfirePendingExpireScheduler {

  private final TriggerMisfirePendingMapper mapper;

  /** 每小时跑一次,过期清理量极小,不需要更频繁。可调:batch.trigger.wheel.misfire-pending-expire-interval。 */
  @Scheduled(fixedDelayString = "${batch.trigger.wheel.misfire-pending-expire-interval:PT1H}")
  @SchedulerLock(name = "misfire_pending_expire", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
  public void expirePending() {
    try {
      int expired = mapper.markExpired(Instant.now());
      if (expired > 0) {
        log.info("misfire pending expired: count={}", expired);
      }
    } catch (RuntimeException e) {
      log.warn("misfire pending expire pass failed, will retry next cycle: {}", e.getMessage());
    }
  }
}
