package io.github.pinpols.batch.trigger.domain;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.quartz.SchedulerException;
import org.springframework.stereotype.Service;

/** Trigger 排水用例入口，隔离 HTTP 控制器与 Quartz 生命周期协调器。 */
@Service
@RequiredArgsConstructor
public class TriggerDrainService {

  private final TriggerGracefulShutdown gracefulShutdown;

  public boolean isDraining() {
    return gracefulShutdown.isDraining();
  }

  public void ensureAcceptingLaunches() {
    if (isDraining()) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.trigger.draining");
    }
  }

  public TriggerDrainStatus status() throws SchedulerException {
    TriggerGracefulShutdown.TriggerDrainStatus status = gracefulShutdown.status();
    return new TriggerDrainStatus(
        status.draining(), status.drainingSince(), status.reason(), status.schedulerStatus());
  }

  public TriggerDrainStatus enable() throws SchedulerException {
    gracefulShutdown.startDraining("manual-enable");
    return status();
  }

  public TriggerDrainStatus disable() throws SchedulerException {
    gracefulShutdown.stopDraining("manual-disable");
    return status();
  }

  public record TriggerDrainStatus(
      boolean draining, Instant drainingSince, String reason, String schedulerStatus) {}
}
