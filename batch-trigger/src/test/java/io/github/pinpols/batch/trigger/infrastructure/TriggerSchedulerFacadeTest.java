package io.github.pinpols.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.CatchUpPolicyType;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;

@ExtendWith(MockitoExtension.class)
class TriggerSchedulerFacadeTest {

  @Mock private TriggerDefinitionLoader triggerDefinitionLoader;
  @Mock private Scheduler scheduler;

  private TriggerSchedulerFacade facade;

  @BeforeEach
  void setUp() {
    facade = new TriggerSchedulerFacade(triggerDefinitionLoader, scheduler);
  }

  // ─── CRON ────────────────────────────────────────────────────────────────────

  @Test
  void shouldScheduleEnabledCronDefinitionsWithCatchUpMetadata() throws Exception {
    TriggerDescriptor dependentCron = cronDescriptor("t1", "JOB_CRON", true);
    dependentCron.setDependsOnJobCode("UPSTREAM_JOB");
    when(triggerDefinitionLoader.loadAll())
        .thenReturn(List.of(dependentCron, cronDescriptor("t1", "JOB_DISABLED", false)));

    facade.registerAll();

    ArgumentCaptor<JobDetail> jobDetailCaptor = ArgumentCaptor.forClass(JobDetail.class);
    ArgumentCaptor<CronTrigger> triggerCaptor = ArgumentCaptor.forClass(CronTrigger.class);
    verify(scheduler).scheduleJob(jobDetailCaptor.capture(), triggerCaptor.capture());

    JobDetail jobDetail = jobDetailCaptor.getValue();
    assertThat(jobDetail.getKey().getName()).isEqualTo("t1:JOB_CRON");
    assertThat(jobDetail.getKey().getGroup()).isEqualTo(TriggerSchedulerFacade.JOB_GROUP);
    assertThat(jobDetail.getJobDataMap())
        .containsEntry(QuartzLaunchJob.CALENDAR_CODE, "BIZ_CAL")
        .containsEntry(QuartzLaunchJob.DEPENDS_ON_JOB_CODE, "UPSTREAM_JOB")
        .containsEntry(QuartzLaunchJob.CATCH_UP_POLICY, CatchUpPolicyType.AUTO.code())
        .containsEntry(QuartzLaunchJob.CATCH_UP_MAX_DAYS, 3);
    assertThat(triggerCaptor.getValue().getCronExpression()).isEqualTo("0 0 1 * * ?");
    assertThat(triggerCaptor.getValue().getMisfireInstruction())
        .isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
  }

  @Test
  void shouldDeleteExistingQuartzJobBeforeReschedulingCron() throws Exception {
    when(triggerDefinitionLoader.loadByJobCode("t1", "JOB_REPLACE"))
        .thenReturn(cronDescriptor("t1", "JOB_REPLACE", true));
    when(scheduler.checkExists(any(JobKey.class))).thenReturn(true);

    facade.registerByJobCode("t1", "JOB_REPLACE");

    var inOrder = inOrder(scheduler);
    inOrder.verify(scheduler).checkExists(any(JobKey.class));
    inOrder.verify(scheduler).deleteJob(any());
    inOrder.verify(scheduler).scheduleJob(any(JobDetail.class), any(CronTrigger.class));
  }

  @Test
  void shouldSkipInvalidCronExpression() throws Exception {
    TriggerDescriptor descriptor = cronDescriptor("t1", "BAD_CRON", true);
    descriptor.setScheduleExpression("not-a-cron");
    when(triggerDefinitionLoader.loadByJobCode("t1", "BAD_CRON")).thenReturn(descriptor);

    facade.registerByJobCode("t1", "BAD_CRON");

    verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(CronTrigger.class));
  }

  // ─── FIXED_RATE ───────────────────────────────────────────────────────────────

  @Test
  void shouldScheduleFixedRateDefinitionWithSimpleTrigger() throws Exception {
    when(triggerDefinitionLoader.loadAll())
        .thenReturn(List.of(fixedRateDescriptor("t1", "JOB_FIXED", "300", true)));

    facade.registerAll();

    ArgumentCaptor<JobDetail> jobDetailCaptor = ArgumentCaptor.forClass(JobDetail.class);
    ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);
    verify(scheduler).scheduleJob(jobDetailCaptor.capture(), triggerCaptor.capture());

    JobDetail jobDetail = jobDetailCaptor.getValue();
    assertThat(jobDetail.getKey().getName()).isEqualTo("t1:JOB_FIXED");
    assertThat(jobDetail.getJobDataMap())
        .containsEntry(QuartzLaunchJob.SCHEDULE_TYPE, "FIXED_RATE")
        .containsEntry(QuartzLaunchJob.SCHEDULE_EXPRESSION, "300");

    Trigger trigger = triggerCaptor.getValue();
    assertThat(trigger).isInstanceOf(SimpleTrigger.class);
    SimpleTrigger simpleTrigger = (SimpleTrigger) trigger;
    assertThat(simpleTrigger.getRepeatInterval()).isEqualTo(300_000L); // ms
    assertThat(simpleTrigger.getRepeatCount()).isEqualTo(SimpleTrigger.REPEAT_INDEFINITELY);
    assertThat(simpleTrigger.getMisfireInstruction())
        .isEqualTo(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_EXISTING_COUNT);
  }

  @Test
  void shouldDeleteExistingQuartzJobBeforeReschedulingFixedRate() throws Exception {
    when(triggerDefinitionLoader.loadByJobCode("t1", "JOB_FR"))
        .thenReturn(fixedRateDescriptor("t1", "JOB_FR", "60", true));
    when(scheduler.checkExists(any(JobKey.class))).thenReturn(true);

    facade.registerByJobCode("t1", "JOB_FR");

    var inOrder = inOrder(scheduler);
    inOrder.verify(scheduler).checkExists(any(JobKey.class));
    inOrder.verify(scheduler).deleteJob(any());
    inOrder.verify(scheduler).scheduleJob(any(JobDetail.class), any(SimpleTrigger.class));
  }

  @Test
  void shouldSkipNonNumericFixedRateExpression() throws Exception {
    TriggerDescriptor descriptor = fixedRateDescriptor("t1", "BAD_FR", "not-a-number", true);
    when(triggerDefinitionLoader.loadByJobCode("t1", "BAD_FR")).thenReturn(descriptor);

    facade.registerByJobCode("t1", "BAD_FR");

    verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(SimpleTrigger.class));
  }

  @Test
  void shouldSkipZeroFixedRateInterval() throws Exception {
    TriggerDescriptor descriptor = fixedRateDescriptor("t1", "ZERO_FR", "0", true);
    when(triggerDefinitionLoader.loadByJobCode("t1", "ZERO_FR")).thenReturn(descriptor);

    facade.registerByJobCode("t1", "ZERO_FR");

    verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(SimpleTrigger.class));
  }

  @Test
  void shouldSkipNegativeFixedRateInterval() throws Exception {
    TriggerDescriptor descriptor = fixedRateDescriptor("t1", "NEG_FR", "-10", true);
    when(triggerDefinitionLoader.loadByJobCode("t1", "NEG_FR")).thenReturn(descriptor);

    facade.registerByJobCode("t1", "NEG_FR");

    verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(SimpleTrigger.class));
  }

  @Test
  void shouldSkipBlankFixedRateExpression() throws Exception {
    TriggerDescriptor descriptor = fixedRateDescriptor("t1", "BLANK_FR", "  ", true);
    when(triggerDefinitionLoader.loadByJobCode("t1", "BLANK_FR")).thenReturn(descriptor);

    facade.registerByJobCode("t1", "BLANK_FR");

    verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(SimpleTrigger.class));
  }

  // ─── 跳过非 scheduled 类型 ─────────────────────────────────────────────────────

  @Test
  void shouldSilentlySkipManualAndEventScheduleTypes() throws Exception {
    when(triggerDefinitionLoader.loadAll())
        .thenReturn(
            List.of(manualDescriptor("t1", "JOB_MANUAL"), eventDescriptor("t1", "JOB_EVENT")));

    facade.registerAll();

    verifyNoInteractions(scheduler);
  }

  // ─── helpers ──────────────────────────────────────────────────────────────────

  private TriggerDescriptor cronDescriptor(String tenantId, String jobCode, boolean enabled) {
    TriggerDescriptor d = new TriggerDescriptor();
    d.setTenantId(tenantId);
    d.setJobCode(jobCode);
    d.setScheduleType("CRON");
    d.setScheduleExpression("0 0 1 * * ?");
    d.setTimezone("UTC");
    d.setTriggerMode("SCHEDULED");
    d.setCalendarCode("BIZ_CAL");
    d.setCatchUpPolicy(CatchUpPolicyType.AUTO.code());
    d.setCatchUpMaxDays(3);
    d.setEnabled(enabled);
    return d;
  }

  private TriggerDescriptor fixedRateDescriptor(
      String tenantId, String jobCode, String intervalSeconds, boolean enabled) {
    TriggerDescriptor d = new TriggerDescriptor();
    d.setTenantId(tenantId);
    d.setJobCode(jobCode);
    d.setScheduleType("FIXED_RATE");
    d.setScheduleExpression(intervalSeconds);
    d.setTimezone("UTC");
    d.setTriggerMode("SCHEDULED");
    d.setCalendarCode(null);
    d.setCatchUpPolicy(CatchUpPolicyType.NONE.code());
    d.setCatchUpMaxDays(0);
    d.setEnabled(enabled);
    return d;
  }

  private TriggerDescriptor manualDescriptor(String tenantId, String jobCode) {
    TriggerDescriptor d = new TriggerDescriptor();
    d.setTenantId(tenantId);
    d.setJobCode(jobCode);
    d.setScheduleType("MANUAL");
    d.setEnabled(true);
    return d;
  }

  private TriggerDescriptor eventDescriptor(String tenantId, String jobCode) {
    TriggerDescriptor d = new TriggerDescriptor();
    d.setTenantId(tenantId);
    d.setJobCode(jobCode);
    d.setScheduleType("EVENT");
    d.setEnabled(true);
    return d;
  }
}
