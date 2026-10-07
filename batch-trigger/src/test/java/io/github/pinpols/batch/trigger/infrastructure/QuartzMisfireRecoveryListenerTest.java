package io.github.pinpols.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.scheduling.TaskScheduler;

@ExtendWith(MockitoExtension.class)
@DisplayName("Quartz misfire 恢复监听器:错失的批处理触发在 JobStore 锁外异步补建恢复 Trigger")
class QuartzMisfireRecoveryListenerTest {

  @Mock
  private Scheduler scheduler;

  @Mock
  private Trigger trigger;

  @Mock
  private TaskScheduler taskScheduler;

  @Test
  @DisplayName("错失触发不得在 Quartz 回调内同步重入调度,而应在回调外补建恢复 Trigger 并保留原始 fire time")
  void shouldScheduleOneShotRecoveryWithOriginalFireTime() throws Exception {
    Instant originalFireTime = Instant.parse("2026-03-27T00:00:00Z");
    JobKey jobKey = JobKey.jobKey("t1:IMPORT_JOB", TriggerSchedulerFacade.JOB_GROUP);
    when(trigger.getKey())
        .thenReturn(TriggerKey.triggerKey("t1:IMPORT_JOB", TriggerSchedulerFacade.JOB_GROUP));
    when(trigger.getJobKey()).thenReturn(jobKey);
    when(trigger.getNextFireTime()).thenReturn(Date.from(originalFireTime));

    QuartzMisfireRecoveryListener listener =
        new QuartzMisfireRecoveryListener(() -> scheduler, taskScheduler);
    listener.triggerMisfired(trigger);

    // Quartz 的 misfire 回调持有 JobStore 锁；回调内不能同步重入 Scheduler。
    verify(scheduler, never()).scheduleJob(any(Trigger.class));
    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler).schedule(task.capture(), any(Instant.class));
    task.getValue().run();

    ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
    verify(scheduler).scheduleJob(captor.capture());
    Trigger recovery = captor.getValue();
    assertThat(recovery.getKey().getGroup())
        .isEqualTo(QuartzMisfireRecoveryListener.RECOVERY_GROUP);
    assertThat(recovery.getJobKey()).isEqualTo(jobKey);
    assertThat(recovery.getJobDataMap().getLongValue(QuartzLaunchJob.MISFIRE_ORIGINAL_FIRE_TIME))
        .isEqualTo(originalFireTime.toEpochMilli());
    verify(scheduler)
        .resumeTriggers(
            GroupMatcher.triggerGroupEquals(QuartzMisfireRecoveryListener.RECOVERY_GROUP));
    verify(scheduler).resumeTrigger(recovery.getKey());
  }

  @Test
  @DisplayName("下一次触发时间为空时,恢复 Trigger 的原始触发时间应回退取上一次 fire time")
  void shouldUsePreviousFireTimeWhenNextFireTimeIsUnavailable() throws Exception {
    Instant previousFireTime = Instant.parse("2026-03-27T00:00:00Z");
    JobKey jobKey = JobKey.jobKey("t1:IMPORT_JOB", TriggerSchedulerFacade.JOB_GROUP);
    when(trigger.getKey())
        .thenReturn(TriggerKey.triggerKey("t1:IMPORT_JOB", TriggerSchedulerFacade.JOB_GROUP));
    when(trigger.getJobKey()).thenReturn(jobKey);
    when(trigger.getNextFireTime()).thenReturn(null);
    when(trigger.getPreviousFireTime()).thenReturn(Date.from(previousFireTime));

    QuartzMisfireRecoveryListener listener =
        new QuartzMisfireRecoveryListener(() -> scheduler, taskScheduler);
    listener.triggerMisfired(trigger);
    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler).schedule(task.capture(), any(Instant.class));
    task.getValue().run();

    ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
    verify(scheduler).scheduleJob(captor.capture());
    assertThat(captor
            .getValue()
            .getJobDataMap()
            .getLongValue(QuartzLaunchJob.MISFIRE_ORIGINAL_FIRE_TIME))
        .isEqualTo(previousFireTime.toEpochMilli());
  }

  @Test
  @DisplayName("非 batch 业务分组的 Trigger 错失时不补跑,既不建恢复 Trigger 也不投递异步补数任务")
  void shouldIgnoreNonBatchTriggerGroups() throws Exception {
    when(trigger.getKey()).thenReturn(TriggerKey.triggerKey("other", "other-group"));

    new QuartzMisfireRecoveryListener(() -> scheduler, taskScheduler).triggerMisfired(trigger);

    verify(scheduler, never()).scheduleJob(org.mockito.ArgumentMatchers.any(Trigger.class));
    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
  }
}
