package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.orchestrator.application.scheduler.WaitingCapacityReleasedEvent;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 在 task 终态提交后合并触发 WAITING 分区重派，缩短容量释放到下一任务派发的空窗。 */
@Slf4j
@Component
class WaitingPartitionDispatchKick {

  private final WaitingPartitionDispatchScheduler waitingPartitionDispatchScheduler;
  private final ResourceSchedulerProperties properties;
  private final TaskScheduler taskScheduler;
  private final Counter requestedCounter;
  private final Counter coalescedCounter;
  private final Counter executedCounter;
  private final AtomicBoolean scheduled = new AtomicBoolean();

  WaitingPartitionDispatchKick(
      WaitingPartitionDispatchScheduler waitingPartitionDispatchScheduler,
      ResourceSchedulerProperties properties,
      @Qualifier("taskScheduler") TaskScheduler taskScheduler,
      MeterRegistry meterRegistry) {
    this.waitingPartitionDispatchScheduler = waitingPartitionDispatchScheduler;
    this.properties = properties;
    this.taskScheduler = taskScheduler;
    this.requestedCounter = Counter.builder("batch.scheduler.waiting_dispatch.kick.requested")
        .description("Committed task outcomes that scheduled a waiting-partition dispatch kick")
        .register(meterRegistry);
    this.coalescedCounter = Counter.builder("batch.scheduler.waiting_dispatch.kick.coalesced")
        .description("Capacity-release events merged into an already scheduled dispatch kick")
        .register(meterRegistry);
    this.executedCounter = Counter.builder("batch.scheduler.waiting_dispatch.kick.executed")
        .description("Waiting-partition dispatch kicks executed after task outcome commit")
        .register(meterRegistry);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void onCapacityReleased(WaitingCapacityReleasedEvent event) {
    if (!properties.isWaitingDispatchKickEnabled()) {
      return;
    }
    if (!scheduled.compareAndSet(false, true)) {
      coalescedCounter.increment();
      return;
    }
    try {
      requestedCounter.increment();
      long delayMillis = Math.max(0L, properties.getWaitingDispatchKickDelayMillis());
      taskScheduler.schedule(this::dispatch, Instant.now().plusMillis(delayMillis));
    } catch (RuntimeException exception) {
      scheduled.set(false);
      log.warn("schedule waiting-partition dispatch kick failed: {}", exception.getMessage());
    }
  }

  private void dispatch() {
    try {
      // 从另一个 Spring bean 调用，@SchedulerLock AOP 仍会生效；多实例只会有一个实际执行。
      executedCounter.increment();
      waitingPartitionDispatchScheduler.dispatchWaitingPartitions();
    } finally {
      scheduled.set(false);
    }
  }
}
