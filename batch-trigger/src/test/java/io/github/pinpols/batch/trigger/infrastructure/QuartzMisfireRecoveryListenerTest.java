package io.github.pinpols.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.trigger.config.TriggerRuntimeProperties;
import io.github.pinpols.batch.trigger.service.ScheduledOccurrenceRecorder;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
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

  @Mock
  private ScheduledOccurrenceRecorder occurrenceRecorder;

  @Test
  @DisplayName("错失触发不得在 Quartz 回调内同步重入调度,而应在回调外补建恢复 Trigger 并保留原始 fire time")
  void shouldScheduleOneShotRecoveryWithOriginalFireTime() throws Exception {
    Instant originalFireTime = Instant.parse("2026-03-27T00:00:00Z");
    JobKey jobKey = JobKey.jobKey("t1:IMPORT_JOB", TriggerSchedulerFacade.JOB_GROUP);
    when(trigger.getKey())
        .thenReturn(TriggerKey.triggerKey("t1:IMPORT_JOB", TriggerSchedulerFacade.JOB_GROUP));
    when(trigger.getJobKey()).thenReturn(jobKey);
    when(trigger.getNextFireTime()).thenReturn(Date.from(originalFireTime));
    when(trigger.getJobDataMap()).thenReturn(workflowIdentityData());
    when(occurrenceRecorder.markMisfirePending("t1", 71L, originalFireTime)).thenReturn(true);
    when(scheduler.getJobDetail(jobKey)).thenReturn(workflowJob(jobKey));

    QuartzMisfireRecoveryListener listener = new QuartzMisfireRecoveryListener(
        () -> scheduler, taskScheduler, occurrenceRecorder, new TriggerRuntimeProperties());
    listener.triggerMisfired(trigger);

    var callbackOrder = inOrder(occurrenceRecorder, taskScheduler);
    callbackOrder.verify(occurrenceRecorder).markMisfirePending("t1", 71L, originalFireTime);
    callbackOrder.verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

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
    assertThat(recovery.getJobKey())
        .isEqualTo(JobKey.jobKey("t1:RECOVERY:71", TriggerSchedulerFacade.JOB_GROUP));
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
    when(trigger.getJobDataMap()).thenReturn(workflowIdentityData());
    when(occurrenceRecorder.markMisfirePending("t1", 71L, previousFireTime)).thenReturn(true);
    when(scheduler.getJobDetail(jobKey)).thenReturn(workflowJob(jobKey));

    QuartzMisfireRecoveryListener listener = new QuartzMisfireRecoveryListener(
        () -> scheduler, taskScheduler, occurrenceRecorder, new TriggerRuntimeProperties());
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

    new QuartzMisfireRecoveryListener(
            () -> scheduler, taskScheduler, occurrenceRecorder, new TriggerRuntimeProperties())
        .triggerMisfired(trigger);

    verify(scheduler, never()).scheduleJob(ArgumentMatchers.any(Trigger.class));
    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  @DisplayName("进程重启后从持久 MISFIRE_PENDING occurrence 重建稳定身份的 Quartz 恢复 Trigger")
  void shouldRestorePendingRecoveryAfterRestart() throws Exception {
    Instant originalFireTime = Instant.parse("2026-03-27T00:00:00Z");
    JobKey jobKey = JobKey.jobKey("t1:WF:FLOW_A", TriggerSchedulerFacade.JOB_GROUP);
    when(occurrenceRecorder.pendingMisfires("t1", 71L, 100)).thenReturn(List.of(originalFireTime));
    when(scheduler.getJobDetail(jobKey)).thenReturn(workflowJob(jobKey));

    new QuartzMisfireRecoveryListener(
            () -> scheduler, taskScheduler, occurrenceRecorder, new TriggerRuntimeProperties())
        .restorePendingMisfires("t1", 71L, "FLOW_A");

    ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
    verify(scheduler).scheduleJob(captor.capture());
    Trigger recovery = captor.getValue();
    assertThat(recovery.getKey().getName())
        .isEqualTo("misfire-recovery-71-" + originalFireTime.toEpochMilli());
    assertThat(recovery.getJobKey())
        .isEqualTo(JobKey.jobKey("t1:RECOVERY:71", TriggerSchedulerFacade.JOB_GROUP));
    assertThat(recovery.getJobDataMap().getLongValue(QuartzLaunchJob.MISFIRE_ORIGINAL_FIRE_TIME))
        .isEqualTo(originalFireTime.toEpochMilli());
  }

  @Test
  @DisplayName("Workflow 停用后已存在的恢复 Trigger 必须从主 Job 重绑到专用恢复 Job")
  void shouldRebindExistingRecoveryTriggerToDedicatedJob() throws Exception {
    Instant originalFireTime = Instant.parse("2026-03-27T00:00:00Z");
    JobKey primaryJobKey = JobKey.jobKey("t1:WF:FLOW_A", TriggerSchedulerFacade.JOB_GROUP);
    JobKey recoveryJobKey = JobKey.jobKey("t1:RECOVERY:71", TriggerSchedulerFacade.JOB_GROUP);
    TriggerKey recoveryTriggerKey = TriggerKey.triggerKey(
        "misfire-recovery-71-" + originalFireTime.toEpochMilli(),
        QuartzMisfireRecoveryListener.RECOVERY_GROUP);
    Trigger existing = TriggerBuilder.newTrigger()
        .withIdentity(recoveryTriggerKey)
        .forJob(primaryJobKey)
        .usingJobData(QuartzLaunchJob.WORKFLOW_VERSION, 3)
        .build();
    JobDetail currentTemplate = workflowJob(recoveryJobKey);
    currentTemplate.getJobDataMap().put(QuartzLaunchJob.WORKFLOW_VERSION, 4);
    when(scheduler.getJobDetail(recoveryJobKey)).thenReturn(currentTemplate);
    when(occurrenceRecorder.pendingMisfires("t1", 71L, 100)).thenReturn(List.of(originalFireTime));
    when(scheduler.checkExists(recoveryTriggerKey)).thenReturn(true);
    when(scheduler.getTrigger(recoveryTriggerKey)).thenReturn(existing);
    when(scheduler.rescheduleJob(eq(recoveryTriggerKey), any(Trigger.class)))
        .thenReturn(Date.from(originalFireTime));

    new QuartzMisfireRecoveryListener(
            () -> scheduler, taskScheduler, occurrenceRecorder, new TriggerRuntimeProperties())
        .restorePendingMisfires("t1", 71L, recoveryJobKey);

    ArgumentCaptor<Trigger> replacement = ArgumentCaptor.forClass(Trigger.class);
    verify(scheduler).rescheduleJob(eq(recoveryTriggerKey), replacement.capture());
    assertThat(replacement.getValue().getKey()).isEqualTo(recoveryTriggerKey);
    assertThat(replacement.getValue().getJobKey()).isEqualTo(recoveryJobKey);
    assertThat(replacement.getValue().getJobDataMap().getIntValue(QuartzLaunchJob.WORKFLOW_VERSION))
        .isEqualTo(3);
    assertThat(replacement
            .getValue()
            .getJobDataMap()
            .getLongValue(QuartzLaunchJob.MISFIRE_ORIGINAL_FIRE_TIME))
        .isEqualTo(originalFireTime.toEpochMilli());
    verify(scheduler, never()).scheduleJob(any(Trigger.class));
  }

  private JobDataMap workflowIdentityData() {
    JobDataMap data = new JobDataMap();
    data.put(QuartzLaunchJob.TENANT_ID, "t1");
    data.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, 71L);
    return data;
  }

  private JobDetail workflowJob(JobKey jobKey) {
    return JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(jobKey)
        .usingJobData(workflowIdentityData())
        .storeDurably()
        .build();
  }
}
