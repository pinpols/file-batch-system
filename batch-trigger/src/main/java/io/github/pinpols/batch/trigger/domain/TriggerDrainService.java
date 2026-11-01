package io.github.pinpols.batch.trigger.domain;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Trigger 排水用例入口，隔离 HTTP 控制器与 Quartz 生命周期协调器。 */
@Service
@RequiredArgsConstructor
public class TriggerDrainService {

  private final TriggerDrainPort drainPort;

  public boolean isDraining() {
    return drainPort.isDraining();
  }

  public void ensureAcceptingLaunches() {
    if (isDraining()) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.trigger.draining");
    }
  }

  public TriggerDrainStatus status() {
    TriggerDrainPort.Status status = drainPort.status();
    return new TriggerDrainStatus(
        status.draining(), status.drainingSince(), status.reason(), status.schedulerStatus());
  }

  public TriggerDrainStatus enable() {
    drainPort.enable();
    return status();
  }

  public TriggerDrainStatus disable() {
    drainPort.disable();
    return status();
  }

  public record TriggerDrainStatus(
      boolean draining, Instant drainingSince, String reason, String schedulerStatus) {}
}
