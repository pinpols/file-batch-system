package io.github.pinpols.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.CatchUpPolicyType;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.trigger.config.TriggerRuntimeProperties;
import io.github.pinpols.batch.trigger.domain.MisfireHandler;
import io.github.pinpols.batch.trigger.domain.TriggerRegistrationService;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.service.UpstreamNotReadyException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;

@ExtendWith(MockitoExtension.class)
@DisplayName("Quartz 触发入口 misfire 决策:按漂移时长与补跑策略分派人工审批、自动补跑、降级或未就绪重试")
class QuartzLaunchJobTest {

  @Mock
  private TriggerService triggerService;

  @Mock
  private MisfireHandler misfireHandler;

  @Mock
  private TriggerRegistrationService triggerRegistrationService;

  @Mock
  private JobExecutionContext context;

  @Mock
  private Scheduler scheduler;

  @Mock
  private JobDetail jobDetail;

  private QuartzLaunchJob job;

  @BeforeEach
  void setUp() {
    TriggerRuntimeProperties properties = new TriggerRuntimeProperties();
    properties.setMisfireCatchUpThresholdSeconds(60L);
    job = new QuartzLaunchJob(
        triggerService,
        misfireHandler,
        properties,
        triggerRegistrationService,
        new SimpleMeterRegistry());
  }

  @Test
  @DisplayName("MANUAL_APPROVAL 策略发生 misfire 时只登记待审批补跑,不直接启动并由人工确认")
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
  @DisplayName("misfire 恢复触发器带回原始计划时刻时,待审批补跑沿用该时刻而非当前触发时间")
  void shouldUsePreservedFireTimeFromMisfireRecoveryTrigger() throws Exception {
    Instant originalFireTime = Instant.parse("2026-03-27T00:00:00Z");
    JobDataMap data = jobDataMap(CatchUpPolicyType.MANUAL_APPROVAL.code(), 2);
    data.put(QuartzLaunchJob.MISFIRE_ORIGINAL_FIRE_TIME, originalFireTime.toEpochMilli());
    when(context.getMergedJobDataMap()).thenReturn(data);
    when(context.getFireTime()).thenReturn(Date.from(Instant.parse("2026-03-27T00:10:00Z")));

    job.execute(context);

    ArgumentCaptor<ScheduledTriggerCommand> captor =
        ArgumentCaptor.forClass(ScheduledTriggerCommand.class);
    verify(triggerService).createPendingCatchUp(captor.capture());
    assertThat(captor.getValue().fireTime()).isEqualTo(originalFireTime);
    verify(triggerService, never()).launchScheduled(any());
  }

  @Test
  @DisplayName("AUTO 策略且漂移未超最大天数时直接以 CATCH_UP 类型补跑,并保留上游依赖作业")
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
    assertThat(captor.getValue().descriptor().getDependsOnJobCode()).isEqualTo("UPSTREAM_JOB");
    verify(triggerService, never()).createPendingCatchUp(any());
    verify(misfireHandler).handle("t1:IMPORT_JOB");
  }

  @Test
  @DisplayName("NONE 策略即使漂移很大也只按 SCHEDULED 正常触发,不产生任何补跑记录")
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
  @DisplayName("AUTO 策略漂移超过最大补跑天数时降级为 SCHEDULED,避免无边界追赶历史数据")
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

  @Test
  @DisplayName("上游未就绪时注册 30 秒后的 Quartz 重试触发器,并携带原始计划时刻与触发类型续跑")
  void shouldScheduleQuartzRetryWhenUpstreamIsNotReady() throws Exception {
    Instant originalFireTime = Instant.parse("2026-03-27T00:00:00Z");
    Instant actualFireTime = Instant.parse("2026-03-27T00:00:05Z");
    when(context.getMergedJobDataMap()).thenReturn(jobDataMap(CatchUpPolicyType.NONE.code(), 2));
    when(context.getScheduledFireTime()).thenReturn(Date.from(originalFireTime));
    when(context.getFireTime()).thenReturn(Date.from(actualFireTime));
    when(context.getScheduler()).thenReturn(scheduler);
    when(context.getJobDetail()).thenReturn(jobDetail);
    when(jobDetail.getKey()).thenReturn(JobKey.jobKey("t1:IMPORT_JOB", "batch-trigger"));
    doThrow(new UpstreamNotReadyException("t1", "IMPORT_JOB", "UPSTREAM_JOB", null))
        .when(triggerService)
        .launchScheduled(any());

    job.execute(context);

    ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);
    verify(scheduler).scheduleJob(triggerCaptor.capture());
    Trigger retry = triggerCaptor.getValue();
    assertThat(retry.getStartTime()).isEqualTo(Date.from(actualFireTime.plusSeconds(30)));
    assertThat(retry.getJobDataMap().getLongValue(QuartzLaunchJob.READINESS_ORIGINAL_FIRE_TIME))
        .isEqualTo(originalFireTime.toEpochMilli());
    assertThat(retry.getJobDataMap().getString(QuartzLaunchJob.READINESS_TRIGGER_TYPE))
        .isEqualTo(TriggerType.SCHEDULED.name());
  }

  @Test
  @DisplayName("未就绪等待超过 readiness 窗口 2 小时后放弃本轮触发,不再注册新的重试触发器")
  void shouldStopReadinessRetryAfterWindowExpires() throws Exception {
    Instant originalFireTime = Instant.parse("2026-03-27T00:00:00Z");
    Instant deferredSince = Instant.parse("2026-03-27T00:00:05Z");
    Instant actualFireTime = deferredSince.plusSeconds(7200);
    JobDataMap data = jobDataMap(CatchUpPolicyType.NONE.code(), 2);
    data.put(QuartzLaunchJob.READINESS_ORIGINAL_FIRE_TIME, originalFireTime.toEpochMilli());
    data.put(QuartzLaunchJob.READINESS_DEFERRED_SINCE, deferredSince.toEpochMilli());
    data.put(QuartzLaunchJob.READINESS_TRIGGER_TYPE, TriggerType.SCHEDULED.name());
    when(context.getMergedJobDataMap()).thenReturn(data);
    when(context.getFireTime()).thenReturn(Date.from(actualFireTime));
    doThrow(new UpstreamNotReadyException("t1", "IMPORT_JOB", "UPSTREAM_JOB", null))
        .when(triggerService)
        .launchScheduled(any());

    job.execute(context);

    verify(context, never()).getScheduler();
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
    jobDataMap.put(QuartzLaunchJob.DEPENDS_ON_JOB_CODE, "UPSTREAM_JOB");
    jobDataMap.put(QuartzLaunchJob.CATCH_UP_POLICY, catchUpPolicy);
    jobDataMap.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, catchUpMaxDays);
    return jobDataMap;
  }
}
