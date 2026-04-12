package com.example.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.trigger.config.TriggerRuntimeProperties;
import com.example.batch.trigger.domain.MisfireHandler;
import com.example.batch.trigger.domain.TriggerRegistrationService;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.service.TriggerService;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

@ExtendWith(MockitoExtension.class)
class QuartzLaunchJobTest {

  @Mock private TriggerService triggerService;
  @Mock private MisfireHandler misfireHandler;
  @Mock private TriggerRegistrationService triggerRegistrationService;
  @Mock private JobExecutionContext context;

  private QuartzLaunchJob job;

  @BeforeEach
  void setUp() {
    TriggerRuntimeProperties properties = new TriggerRuntimeProperties();
    properties.setMisfireCatchUpThresholdSeconds(60L);
    job =
        new QuartzLaunchJob(
            triggerService, misfireHandler, properties, triggerRegistrationService);
  }

  @Test
  void shouldCreatePendingCatchUpWhenManualApprovalPolicyMisfires() throws Exception {
    when(context.getMergedJobDataMap())
        .thenReturn(jobDataMap(CatchUpPolicyType.MANUAL_APPROVAL.code(), 2));
    when(context.getScheduledFireTime())
        .thenReturn(Date.from(Instant.parse("2026-03-27T00:00:00Z")));
    when(context.getFireTime()).thenReturn(Date.from(Instant.parse("2026-03-27T00:10:00Z")));

    job.execute(context);

    ArgumentCaptor<ScheduledTriggerCommand> captor =
        ArgumentCaptor.forClass(ScheduledTriggerCommand.class);
    verify(triggerService).createPendingCatchUp(captor.capture());
    assertThat(captor.getValue().triggerType()).isEqualTo(TriggerType.CATCH_UP);
    assertThat(captor.getValue().descriptor().getCatchUpPolicy())
        .isEqualTo(CatchUpPolicyType.MANUAL_APPROVAL.code());
    assertThat(captor.getValue().descriptor().getCatchUpMaxDays()).isEqualTo(2);
    verify(misfireHandler).handle("t1:IMPORT_JOB");
    verify(triggerService, never()).launchScheduled(any());
  }

  @Test
  void shouldLaunchCatchUpWhenAutoPolicyIsWithinMaxDays() throws Exception {
    when(context.getMergedJobDataMap()).thenReturn(jobDataMap(CatchUpPolicyType.AUTO.code(), 2));
    when(context.getScheduledFireTime())
        .thenReturn(Date.from(Instant.parse("2026-03-25T00:00:00Z")));
    when(context.getFireTime()).thenReturn(Date.from(Instant.parse("2026-03-26T00:01:00Z")));

    job.execute(context);

    ArgumentCaptor<ScheduledTriggerCommand> captor =
        ArgumentCaptor.forClass(ScheduledTriggerCommand.class);
    verify(triggerService).launchScheduled(captor.capture());
    assertThat(captor.getValue().triggerType()).isEqualTo(TriggerType.CATCH_UP);
    assertThat(captor.getValue().descriptor().getCatchUpPolicy())
        .isEqualTo(CatchUpPolicyType.AUTO.code());
    verify(triggerService, never()).createPendingCatchUp(any());
    verify(misfireHandler).handle("t1:IMPORT_JOB");
  }

  @Test
  void shouldSkipApplicationCatchUpWhenPolicyIsNoneDespiteDrift() throws Exception {
    when(context.getMergedJobDataMap()).thenReturn(jobDataMap(CatchUpPolicyType.NONE.code(), 2));
    when(context.getScheduledFireTime())
        .thenReturn(Date.from(Instant.parse("2026-03-25T00:00:00Z")));
    when(context.getFireTime()).thenReturn(Date.from(Instant.parse("2026-03-26T00:10:00Z")));

    job.execute(context);

    ArgumentCaptor<ScheduledTriggerCommand> captor =
        ArgumentCaptor.forClass(ScheduledTriggerCommand.class);
    verify(triggerService).launchScheduled(captor.capture());
    assertThat(captor.getValue().triggerType()).isEqualTo(TriggerType.SCHEDULED);
    verify(triggerService, never()).createPendingCatchUp(any());
    verify(misfireHandler).handle("t1:IMPORT_JOB");
  }

  @Test
  void shouldFallBackToScheduledWhenMisfireExceedsMaxDays() throws Exception {
    when(context.getMergedJobDataMap()).thenReturn(jobDataMap(CatchUpPolicyType.AUTO.code(), 1));
    when(context.getScheduledFireTime())
        .thenReturn(Date.from(Instant.parse("2026-03-23T00:00:00Z")));
    when(context.getFireTime()).thenReturn(Date.from(Instant.parse("2026-03-26T00:01:00Z")));

    job.execute(context);

    ArgumentCaptor<ScheduledTriggerCommand> captor =
        ArgumentCaptor.forClass(ScheduledTriggerCommand.class);
    verify(triggerService).launchScheduled(captor.capture());
    assertThat(captor.getValue().triggerType()).isEqualTo(TriggerType.SCHEDULED);
    verify(triggerService, never()).createPendingCatchUp(any());
    verify(misfireHandler).handle("t1:IMPORT_JOB");
  }

  private JobDataMap jobDataMap(String catchUpPolicy, Integer catchUpMaxDays) {
    JobDataMap jobDataMap = new JobDataMap();
    jobDataMap.put(QuartzLaunchJob.TENANT_ID, "t1");
    jobDataMap.put(QuartzLaunchJob.JOB_CODE, "IMPORT_JOB");
    jobDataMap.put(QuartzLaunchJob.SCHEDULE_TYPE, "CRON");
    jobDataMap.put(QuartzLaunchJob.SCHEDULE_EXPRESSION, "0 0 1 * * ?");
    jobDataMap.put(QuartzLaunchJob.TIMEZONE, "UTC");
    jobDataMap.put(QuartzLaunchJob.TRIGGER_MODE, "SCHEDULED");
    jobDataMap.put(QuartzLaunchJob.CALENDAR_CODE, "BIZ_CAL");
    jobDataMap.put(QuartzLaunchJob.CATCH_UP_POLICY, catchUpPolicy);
    jobDataMap.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, catchUpMaxDays);
    return jobDataMap;
  }
}
