package io.github.pinpols.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;

@ExtendWith(MockitoExtension.class)
class QuartzMisfireRecoveryListenerTest {

  @Mock private Scheduler scheduler;
  @Mock private Trigger trigger;

  @Test
  void shouldScheduleOneShotRecoveryWithOriginalFireTime() throws Exception {
    Instant originalFireTime = Instant.parse("2026-03-27T00:00:00Z");
    JobKey jobKey = JobKey.jobKey("t1:IMPORT_JOB", TriggerSchedulerFacade.JOB_GROUP);
    when(trigger.getKey())
        .thenReturn(TriggerKey.triggerKey("t1:IMPORT_JOB", TriggerSchedulerFacade.JOB_GROUP));
    when(trigger.getJobKey()).thenReturn(jobKey);
    when(trigger.getNextFireTime()).thenReturn(Date.from(originalFireTime));

    new QuartzMisfireRecoveryListener(() -> scheduler).triggerMisfired(trigger);

    ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
    verify(scheduler).scheduleJob(captor.capture());
    Trigger recovery = captor.getValue();
    assertThat(recovery.getKey().getGroup())
        .isEqualTo(QuartzMisfireRecoveryListener.RECOVERY_GROUP);
    assertThat(recovery.getJobKey()).isEqualTo(jobKey);
    assertThat(recovery.getJobDataMap().getLongValue(QuartzLaunchJob.MISFIRE_ORIGINAL_FIRE_TIME))
        .isEqualTo(originalFireTime.toEpochMilli());
  }

  @Test
  void shouldIgnoreNonBatchTriggerGroups() throws Exception {
    when(trigger.getKey()).thenReturn(TriggerKey.triggerKey("other", "other-group"));

    new QuartzMisfireRecoveryListener(() -> scheduler).triggerMisfired(trigger);

    verify(scheduler, never()).scheduleJob(org.mockito.ArgumentMatchers.any(Trigger.class));
  }
}
