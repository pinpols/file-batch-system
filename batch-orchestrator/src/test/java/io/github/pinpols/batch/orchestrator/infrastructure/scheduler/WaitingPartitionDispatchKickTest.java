package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.orchestrator.application.scheduler.WaitingCapacityReleasedEvent;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

@DisplayName("等待分片派发合并触发:容量释放事件在计划派发执行前的合并与延迟调度行为")
class WaitingPartitionDispatchKickTest {

  @Test
  @DisplayName("计划派发尚未执行时多次容量释放合并为一次调度,执行后再次释放重新安排派发")
  void shouldCoalesceCapacityReleases_whenScheduledDispatchNotYetExecuted() {
    WaitingPartitionDispatchScheduler dispatchScheduler =
        org.mockito.Mockito.mock(WaitingPartitionDispatchScheduler.class);
    TaskScheduler taskScheduler = org.mockito.Mockito.mock(TaskScheduler.class);
    ResourceSchedulerProperties properties = new ResourceSchedulerProperties();
    properties.setWaitingDispatchKickDelayMillis(0);
    WaitingPartitionDispatchKick kick = new WaitingPartitionDispatchKick(
        dispatchScheduler, properties, taskScheduler, new SimpleMeterRegistry());

    kick.onCapacityReleased(new WaitingCapacityReleasedEvent("tenant-a"));
    kick.onCapacityReleased(new WaitingCapacityReleasedEvent("tenant-b"));

    ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler).schedule(runnable.capture(), any(Instant.class));
    runnable.getValue().run();
    verify(dispatchScheduler).dispatchWaitingPartitions();

    kick.onCapacityReleased(new WaitingCapacityReleasedEvent("tenant-c"));
    verify(taskScheduler, org.mockito.Mockito.times(2))
        .schedule(any(Runnable.class), any(Instant.class));
  }
}
