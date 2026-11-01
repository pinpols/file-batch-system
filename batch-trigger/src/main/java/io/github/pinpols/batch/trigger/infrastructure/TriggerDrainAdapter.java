package io.github.pinpols.batch.trigger.infrastructure;

import io.github.pinpols.batch.common.exception.SystemException;
import io.github.pinpols.batch.trigger.domain.TriggerDrainPort;
import lombok.RequiredArgsConstructor;
import org.quartz.SchedulerException;
import org.springframework.stereotype.Component;

/** 将排水用例适配到 Quartz 生命周期协调器。 */
@Component
@RequiredArgsConstructor
public class TriggerDrainAdapter implements TriggerDrainPort {
  private final TriggerGracefulShutdown shutdown;

  @Override
  public boolean isDraining() {
    return shutdown.isDraining();
  }

  @Override
  public void enable() {
    try {
      shutdown.startDraining("manual-enable");
    } catch (SchedulerException error) {
      throw new SystemException("调度器排水操作失败", error);
    }
  }

  @Override
  public void disable() {
    try {
      shutdown.stopDraining("manual-disable");
    } catch (SchedulerException error) {
      throw new SystemException("调度器排水操作失败", error);
    }
  }

  @Override
  public Status status() {
    try {
      TriggerGracefulShutdown.TriggerDrainStatus status = shutdown.status();
      return new Status(
          status.draining(), status.drainingSince(), status.reason(), status.schedulerStatus());
    } catch (SchedulerException error) {
      throw new SystemException("读取调度器排水状态失败", error);
    }
  }
}
