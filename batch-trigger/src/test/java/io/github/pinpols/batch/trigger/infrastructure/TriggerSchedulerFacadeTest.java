package io.github.pinpols.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.CatchUpPolicyType;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("Quartz 调度门面:CRON/FIXED_RATE 注册替换、启停暂停顺序与并发管理保护")
class TriggerSchedulerFacadeTest {

  @Mock
  private TriggerDefinitionLoader triggerDefinitionLoader;

  @Mock
  private Scheduler scheduler;

  private TriggerSchedulerFacade facade;

  @BeforeEach
  void setUp() {
    facade = new TriggerSchedulerFacade(triggerDefinitionLoader, scheduler);
  }

  // ─── CRON ────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("启用的 CRON 作业按 DB 描述注册,并携带日历、依赖、补跑策略与 misfire 指令")
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
  @DisplayName("重新注册 CRON 前先删除已存在的 Quartz 作业,保证以 DB 表达式覆盖")
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
  @DisplayName("单作业注册先把 enabled 落库再加载描述下发,避免调度成功但库里仍停用")
  void shouldPersistEnabledBeforeRegisteringSingleJob() throws Exception {
    when(triggerDefinitionLoader.loadByJobCode("t1", "JOB_REENABLE"))
        .thenReturn(cronDescriptor("t1", "JOB_REENABLE", true));

    facade.registerByJobCode("t1", "JOB_REENABLE");

    var inOrder = inOrder(triggerDefinitionLoader, scheduler);
    inOrder.verify(triggerDefinitionLoader).setEnabled("t1", "JOB_REENABLE", true);
    inOrder.verify(triggerDefinitionLoader).loadByJobCode("t1", "JOB_REENABLE");
    inOrder.verify(scheduler).scheduleJob(any(JobDetail.class), any(CronTrigger.class));
  }

  @Test
  @DisplayName("单作业注销先删 Quartz 再落库置停用,避免留下无法解释的残留调度")
  void shouldPersistDisabledAfterUnregisteringSingleJob() throws Exception {
    when(scheduler.checkExists(JobKey.jobKey("t1:JOB_STOP", TriggerSchedulerFacade.JOB_GROUP)))
        .thenReturn(true);

    facade.unregisterByJobCode("t1", "JOB_STOP");

    var inOrder = inOrder(scheduler, triggerDefinitionLoader);
    inOrder
        .verify(scheduler)
        .checkExists(JobKey.jobKey("t1:JOB_STOP", TriggerSchedulerFacade.JOB_GROUP));
    inOrder
        .verify(scheduler)
        .deleteJob(JobKey.jobKey("t1:JOB_STOP", TriggerSchedulerFacade.JOB_GROUP));
    inOrder.verify(triggerDefinitionLoader).setEnabled("t1", "JOB_STOP", false);
  }

  @Test
  @DisplayName("暂停单作业与注销同语义:删除 Quartz 作业后落库置停用")
  void shouldDeleteQuartzJobAndPersistDisabledWhenPausingSingleJob() throws Exception {
    when(scheduler.checkExists(JobKey.jobKey("t1:JOB_PAUSE", TriggerSchedulerFacade.JOB_GROUP)))
        .thenReturn(true);

    facade.pauseByJobCode("t1", "JOB_PAUSE");

    var inOrder = inOrder(scheduler, triggerDefinitionLoader);
    inOrder
        .verify(scheduler)
        .checkExists(JobKey.jobKey("t1:JOB_PAUSE", TriggerSchedulerFacade.JOB_GROUP));
    inOrder
        .verify(scheduler)
        .deleteJob(JobKey.jobKey("t1:JOB_PAUSE", TriggerSchedulerFacade.JOB_GROUP));
    inOrder.verify(triggerDefinitionLoader).setEnabled("t1", "JOB_PAUSE", false);
  }

  @Test
  @DisplayName("恢复单作业先落库置启用,再按最新描述重新下发调度")
  void shouldPersistEnabledAndScheduleWhenResumingSingleJob() throws Exception {
    when(triggerDefinitionLoader.loadByJobCode("t1", "JOB_RESUME"))
        .thenReturn(cronDescriptor("t1", "JOB_RESUME", true));

    facade.resumeByJobCode("t1", "JOB_RESUME");

    var inOrder = inOrder(triggerDefinitionLoader, scheduler);
    inOrder.verify(triggerDefinitionLoader).setEnabled("t1", "JOB_RESUME", true);
    inOrder.verify(triggerDefinitionLoader).loadByJobCode("t1", "JOB_RESUME");
    inOrder.verify(scheduler).scheduleJob(any(JobDetail.class), any(CronTrigger.class));
  }

  @Test
  @DisplayName("cron 表达式非法时跳过该作业,不向 Quartz 注册半成品触发器")
  void shouldSkipInvalidCronExpression() throws Exception {
    TriggerDescriptor descriptor = cronDescriptor("t1", "BAD_CRON", true);
    descriptor.setScheduleExpression("not-a-cron");
    when(triggerDefinitionLoader.loadByJobCode("t1", "BAD_CRON")).thenReturn(descriptor);

    facade.registerByJobCode("t1", "BAD_CRON");

    verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(CronTrigger.class));
  }

  // ─── FIXED_RATE ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("FIXED_RATE 按秒换算成毫秒周期注册为永久重复触发器,并采用保留计数的 misfire 策略")
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
  @DisplayName("重新注册 FIXED_RATE 前同样先删旧作业,避免周期任务双份执行")
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
  @DisplayName("FIXED_RATE 间隔非数字时跳过注册,不让解析异常破坏整批调度")
  void shouldSkipNonNumericFixedRateExpression() throws Exception {
    TriggerDescriptor descriptor = fixedRateDescriptor("t1", "BAD_FR", "not-a-number", true);
    when(triggerDefinitionLoader.loadByJobCode("t1", "BAD_FR")).thenReturn(descriptor);

    facade.registerByJobCode("t1", "BAD_FR");

    verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(SimpleTrigger.class));
  }

  @Test
  @DisplayName("FIXED_RATE 间隔为 0 属非法配置,跳过注册以免形成空转触发器")
  void shouldSkipZeroFixedRateInterval() throws Exception {
    TriggerDescriptor descriptor = fixedRateDescriptor("t1", "ZERO_FR", "0", true);
    when(triggerDefinitionLoader.loadByJobCode("t1", "ZERO_FR")).thenReturn(descriptor);

    facade.registerByJobCode("t1", "ZERO_FR");

    verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(SimpleTrigger.class));
  }

  @Test
  @DisplayName("FIXED_RATE 间隔为负数属非法配置,跳过注册而非写入调度器")
  void shouldSkipNegativeFixedRateInterval() throws Exception {
    TriggerDescriptor descriptor = fixedRateDescriptor("t1", "NEG_FR", "-10", true);
    when(triggerDefinitionLoader.loadByJobCode("t1", "NEG_FR")).thenReturn(descriptor);

    facade.registerByJobCode("t1", "NEG_FR");

    verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(SimpleTrigger.class));
  }

  @Test
  @DisplayName("FIXED_RATE 间隔为空白字符串时同样跳过注册,不写入调度器")
  void shouldSkipBlankFixedRateExpression() throws Exception {
    TriggerDescriptor descriptor = fixedRateDescriptor("t1", "BLANK_FR", "  ", true);
    when(triggerDefinitionLoader.loadByJobCode("t1", "BLANK_FR")).thenReturn(descriptor);

    facade.registerByJobCode("t1", "BLANK_FR");

    verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(SimpleTrigger.class));
  }

  // ─── 跳过非 scheduled 类型 ─────────────────────────────────────────────────────

  @Test
  @DisplayName("MANUAL 与 EVENT 描述不参与 Quartz 调度,注册时不得与调度器交互")
  void shouldSilentlySkipManualAndEventScheduleTypes() {
    when(triggerDefinitionLoader.loadAll())
        .thenReturn(
            List.of(manualDescriptor("t1", "JOB_MANUAL"), eventDescriptor("t1", "JOB_EVENT")));

    facade.registerAll();

    verifyNoInteractions(scheduler);
  }

  @Test
  @DisplayName("管理操作串行化:锁被占用时并发调用立即抛 TriggerSchedulerBusyException 并带操作名")
  void shouldFailFastWhenConcurrentManagementOperationIsAlreadyRunning() throws Exception {
    facade = new TriggerSchedulerFacade(triggerDefinitionLoader, scheduler, Duration.ofMillis(50));
    CountDownLatch enteredQuartzCall = new CountDownLatch(1);
    CountDownLatch releaseQuartzCall = new CountDownLatch(1);
    doAnswer(invocation -> {
          enteredQuartzCall.countDown();
          assertThat(releaseQuartzCall.await(2, TimeUnit.SECONDS)).isTrue();
          return null;
        })
        .when(scheduler)
        .pauseAll();

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> firstOperation = executor.submit(facade::pauseAll);
      assertThat(enteredQuartzCall.await(1, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(facade::resumeAll)
          .isInstanceOf(TriggerSchedulerBusyException.class)
          .hasMessageContaining("resumeAll");

      releaseQuartzCall.countDown();
      firstOperation.get(1, TimeUnit.SECONDS);
    } finally {
      releaseQuartzCall.countDown();
      executor.shutdownNow();
    }
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
