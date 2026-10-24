package io.github.pinpols.batch.trigger.infrastructure.scheduler;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.mapper.TriggerMisfirePendingMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Converges misfire approval and expiry decisions into their linked request and occurrence state. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerMisfirePendingReconciler {

  private final TriggerMisfirePendingMapper pendingMapper;
  private final TriggerGracefulShutdown gracefulShutdown;

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    reconcile();
  }

  @Scheduled(fixedDelayString = "${batch.trigger.misfire-pending-reconcile-interval-millis:30000}")
  @SchedulerLock(
      name = "trigger_misfire_pending_reconciler",
      lockAtMostFor = "PT2M",
      lockAtLeastFor = "PT5S")
  public void reconcile() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    try {
      int approved = pendingMapper.approveCentrallyApproved();
      int rejected = pendingMapper.rejectAndCloseCentrallyRejected();
      int expired = pendingMapper.expireAndCloseUnlaunched(Instant.now());
      if (approved > 0 || rejected > 0 || expired > 0) {
        log.info(
            "misfire pending state reconciled: approvedOccurrences={}, rejectedOccurrences={},"
                + " expiredOccurrences={}",
            approved,
            rejected,
            expired);
      }
    } catch (RuntimeException exception) {
      SwallowedExceptionLogger.warn(
          TriggerMisfirePendingReconciler.class, "misfire-pending-reconcile-failed", exception);
    }
  }
}
