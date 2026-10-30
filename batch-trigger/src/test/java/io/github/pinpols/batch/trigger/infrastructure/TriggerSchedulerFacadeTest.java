package io.github.pinpols.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.CatchUpPolicyType;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.trigger.config.TriggerRuntimeProperties;
import io.github.pinpols.batch.trigger.domain.MisfireHandler;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.infrastructure.readiness.ReadinessWaitHandoverClient;
import io.github.pinpols.batch.trigger.infrastructure.scheduler.TriggerReconciler;
import io.github.pinpols.batch.trigger.service.ScheduledOccurrenceRecorder;
import io.github.pinpols.batch.trigger.service.TriggerScheduledOccurrencePlanner;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.service.UpstreamNotReadyException;
import io.github.pinpols.batch.trigger.support.ReadinessWaitWindow;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.scheduling.TaskScheduler;

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
    dependentCron.setDependsOnJobCodes(List.of("UPSTREAM_JOB", "UPSTREAM_AUDIT"));
    when(triggerDefinitionLoader.loadAll())
        .thenReturn(List.of(dependentCron, cronDescriptor("t1", "JOB_DISABLED", false)));

    facade.registerAll();

    ArgumentCaptor<JobDetail> jobDetailCaptor = ArgumentCaptor.forClass(JobDetail.class);
    ArgumentCaptor<CronTrigger> triggerCaptor = ArgumentCaptor.forClass(CronTrigger.class);
    verify(scheduler).scheduleJob(jobDetailCaptor.capture(), triggerCaptor.capture());

    JobDetail jobDetail = jobDetailCaptor.getValue();
    assertThat(jobDetail.getKey().getName()).isEqualTo("t1:WF:WF_JOB_CRON");
    assertThat(jobDetail.getKey().getGroup()).isEqualTo(TriggerSchedulerFacade.JOB_GROUP);
    assertThat(jobDetail.getJobDataMap())
        .containsEntry(QuartzLaunchJob.CALENDAR_CODE, "BIZ_CAL")
        .containsEntry(QuartzLaunchJob.DEPENDS_ON_JOB_CODE, "UPSTREAM_JOB")
        .containsEntry(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, 42L)
        .containsEntry(QuartzLaunchJob.WORKFLOW_CODE, "WF_JOB_CRON")
        .containsEntry(QuartzLaunchJob.WORKFLOW_VERSION, 3)
        .containsEntry(
            QuartzLaunchJob.DEPENDS_ON_JOB_CODES, List.of("UPSTREAM_JOB", "UPSTREAM_AUDIT"))
        .containsEntry(QuartzLaunchJob.CATCH_UP_POLICY, CatchUpPolicyType.AUTO.code())
        .containsEntry(QuartzLaunchJob.CATCH_UP_MAX_DAYS, 3);
    assertThat(triggerCaptor.getValue().getCronExpression()).isEqualTo("0 0 1 * * ?");
    assertThat(triggerCaptor.getValue().getJobDataMap())
        .containsEntry(QuartzLaunchJob.TENANT_ID, "t1")
        .containsEntry(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, 42L);
    assertThat(triggerCaptor.getValue().getMisfireInstruction())
        .isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
  }

  @Test
  @DisplayName("重新注册 CRON 只替换主trigger，不删除附着的等待回调")
  void shouldReplacePrimaryCronWithoutDeletingQuartzJob() throws Exception {
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_JOB_REPLACE"))
        .thenReturn(cronDescriptor("t1", "JOB_REPLACE", true));
    when(scheduler.checkExists(any(JobKey.class))).thenReturn(true);

    facade.registerByWorkflowCode("t1", "WF_JOB_REPLACE");

    var inOrder = inOrder(scheduler);
    inOrder.verify(scheduler).checkExists(any(JobKey.class));
    inOrder
        .verify(scheduler)
        .scheduleJob(any(JobDetail.class), ArgumentMatchers.anySet(), eq(true));
    verify(scheduler, never()).deleteJob(any(JobKey.class));
  }

  @Test
  @DisplayName("Workflow 注册先把 schedule_enabled 落库再加载描述下发")
  void shouldPersistScheduleEnabledBeforeRegisteringWorkflow() throws Exception {
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_JOB_REENABLE"))
        .thenReturn(cronDescriptor("t1", "JOB_REENABLE", true));

    facade.registerByWorkflowCode("t1", "WF_JOB_REENABLE");

    var inOrder = inOrder(triggerDefinitionLoader, scheduler);
    inOrder
        .verify(triggerDefinitionLoader)
        .setWorkflowScheduleEnabled("t1", "WF_JOB_REENABLE", true);
    inOrder.verify(triggerDefinitionLoader).loadByWorkflowCode("t1", "WF_JOB_REENABLE");
    inOrder.verify(scheduler).scheduleJob(any(JobDetail.class), any(CronTrigger.class));
  }

  @Test
  @DisplayName("对账重新读取当前调度状态,旧启用快照不得撤销暂停")
  void shouldNotReenablePausedWorkflowFromStaleReconcileSnapshot() throws Exception {
    String workflowCode = "WF_JOB_PAUSE_RACE";
    JobKey jobKey = JobKey.jobKey("t1:WF:" + workflowCode, TriggerSchedulerFacade.JOB_GROUP);
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", workflowCode))
        .thenReturn(cronDescriptor("t1", "JOB_PAUSE_RACE", false));
    when(scheduler.checkExists(jobKey)).thenReturn(true);

    facade.reconcileByWorkflowCode("t1", workflowCode);

    verify(triggerDefinitionLoader).loadByWorkflowCode("t1", workflowCode);
    verify(triggerDefinitionLoader, never())
        .setWorkflowScheduleEnabled(eq("t1"), eq(workflowCode), anyBoolean());
    verify(scheduler).deleteJob(jobKey);
    verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(CronTrigger.class));
  }

  @Test
  @DisplayName("对账只按当前启用描述注册 Quartz,不改写 Workflow 调度状态")
  void shouldScheduleCurrentEnabledDescriptorWithoutWritingScheduleState() throws Exception {
    String workflowCode = "WF_JOB_RECONCILE";
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", workflowCode))
        .thenReturn(cronDescriptor("t1", "JOB_RECONCILE", true));

    facade.reconcileByWorkflowCode("t1", workflowCode);

    verify(triggerDefinitionLoader, never())
        .setWorkflowScheduleEnabled(eq("t1"), eq(workflowCode), anyBoolean());
    verify(scheduler).scheduleJob(any(JobDetail.class), any(CronTrigger.class));
  }

  @Test
  @DisplayName("按 Workflow Code 注册组合流程，不依赖合成 JobCode")
  void shouldRegisterComposedWorkflowWithoutJobCode() throws Exception {
    TriggerDescriptor descriptor = cronDescriptor("t1", "IGNORED", true);
    descriptor.setJobCode(null);
    descriptor.setWorkflowCode("COMPOSED_FLOW");
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "COMPOSED_FLOW")).thenReturn(descriptor);

    facade.registerByWorkflowCode("t1", "COMPOSED_FLOW");

    ArgumentCaptor<JobDetail> jobCaptor = ArgumentCaptor.forClass(JobDetail.class);
    verify(scheduler).scheduleJob(jobCaptor.capture(), any(CronTrigger.class));
    assertThat(jobCaptor.getValue().getKey().getName()).isEqualTo("t1:WF:COMPOSED_FLOW");
    assertThat(jobCaptor.getValue().getJobDataMap())
        .containsEntry(QuartzLaunchJob.WORKFLOW_CODE, "COMPOSED_FLOW")
        .containsEntry(QuartzLaunchJob.JOB_CODE, null);
    verify(triggerDefinitionLoader).setWorkflowScheduleEnabled("t1", "COMPOSED_FLOW", true);
  }

  @Test
  @DisplayName("单作业注销先删 Quartz 再落库置停用,避免留下无法解释的残留调度")
  void shouldPersistDisabledAfterUnregisteringSingleJob() throws Exception {
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_JOB_STOP"))
        .thenReturn(cronDescriptor("t1", "JOB_STOP", true));
    when(scheduler.checkExists(
            JobKey.jobKey("t1:WF:WF_JOB_STOP", TriggerSchedulerFacade.JOB_GROUP)))
        .thenReturn(true);

    facade.unregisterByWorkflowCode("t1", "WF_JOB_STOP");

    var inOrder = inOrder(scheduler, triggerDefinitionLoader);
    inOrder.verify(triggerDefinitionLoader).loadByWorkflowCode("t1", "WF_JOB_STOP");
    inOrder
        .verify(scheduler)
        .checkExists(JobKey.jobKey("t1:WF:WF_JOB_STOP", TriggerSchedulerFacade.JOB_GROUP));
    inOrder
        .verify(scheduler)
        .deleteJob(JobKey.jobKey("t1:WF:WF_JOB_STOP", TriggerSchedulerFacade.JOB_GROUP));
    inOrder.verify(triggerDefinitionLoader).setWorkflowScheduleEnabled("t1", "WF_JOB_STOP", false);
  }

  @Test
  @DisplayName("暂停单作业与注销同语义:删除 Quartz 作业后落库置停用")
  void shouldDeleteQuartzJobAndPersistDisabledWhenPausingSingleJob() throws Exception {
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_JOB_PAUSE"))
        .thenReturn(cronDescriptor("t1", "JOB_PAUSE", true));
    when(scheduler.checkExists(
            JobKey.jobKey("t1:WF:WF_JOB_PAUSE", TriggerSchedulerFacade.JOB_GROUP)))
        .thenReturn(true);

    facade.pauseByWorkflowCode("t1", "WF_JOB_PAUSE");

    var inOrder = inOrder(scheduler, triggerDefinitionLoader);
    inOrder.verify(triggerDefinitionLoader).loadByWorkflowCode("t1", "WF_JOB_PAUSE");
    inOrder
        .verify(scheduler)
        .checkExists(JobKey.jobKey("t1:WF:WF_JOB_PAUSE", TriggerSchedulerFacade.JOB_GROUP));
    inOrder
        .verify(scheduler)
        .deleteJob(JobKey.jobKey("t1:WF:WF_JOB_PAUSE", TriggerSchedulerFacade.JOB_GROUP));
    inOrder.verify(triggerDefinitionLoader).setWorkflowScheduleEnabled("t1", "WF_JOB_PAUSE", false);
  }

  @Test
  @DisplayName("暂停前将挂在 Workflow Job 上的 misfire recovery trigger 转到专用恢复 Job")
  void shouldRebindMisfireRecoveryTriggersBeforeDeletingWorkflowJob() throws Exception {
    TriggerDescriptor descriptor = cronDescriptor("t1", "JOB_PAUSE_WITH_MISFIRE", true);
    JobKey workflowJobKey = JobKey.jobKey("t1:WF:WF_JOB_PAUSE_WITH_MISFIRE", "batch-trigger");
    JobKey recoveryJobKey = JobKey.jobKey("t1:RECOVERY:42", "batch-trigger");
    TriggerKey recoveryTriggerKey = TriggerKey.triggerKey(
        "misfire-recovery-42-1792585420000", QuartzMisfireRecoveryListener.RECOVERY_GROUP);
    Trigger pendingRecovery = TriggerBuilder.newTrigger()
        .withIdentity(recoveryTriggerKey)
        .forJob(workflowJobKey)
        .startNow()
        .withSchedule(SimpleScheduleBuilder.simpleSchedule())
        .build();
    List<? extends Trigger> attachedTriggers = List.of(pendingRecovery);
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_JOB_PAUSE_WITH_MISFIRE"))
        .thenReturn(descriptor);
    when(scheduler.checkExists(workflowJobKey)).thenReturn(true);
    JobDetail originalJob = JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(workflowJobKey)
        .usingJobData(QuartzLaunchJob.TENANT_ID, "t1")
        .usingJobData(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, 42L)
        .usingJobData(QuartzLaunchJob.WORKFLOW_VERSION, 3)
        .storeDurably()
        .build();
    when(scheduler.getJobDetail(workflowJobKey)).thenReturn(originalJob);
    doReturn(attachedTriggers).when(scheduler).getTriggersOfJob(workflowJobKey);
    when(scheduler.checkExists(recoveryJobKey)).thenReturn(false);
    when(scheduler.rescheduleJob(eq(recoveryTriggerKey), any(Trigger.class)))
        .thenReturn(new Date());

    facade.pauseByWorkflowCode("t1", "WF_JOB_PAUSE_WITH_MISFIRE");

    ArgumentCaptor<Trigger> rebound = ArgumentCaptor.forClass(Trigger.class);
    verify(scheduler).addJob(any(JobDetail.class), eq(false));
    verify(scheduler).rescheduleJob(eq(recoveryTriggerKey), rebound.capture());
    assertThat(rebound.getValue().getJobKey()).isEqualTo(recoveryJobKey);
    var inOrder = inOrder(scheduler, triggerDefinitionLoader);
    inOrder.verify(scheduler).rescheduleJob(eq(recoveryTriggerKey), any(Trigger.class));
    inOrder.verify(scheduler).deleteJob(workflowJobKey);
    inOrder
        .verify(triggerDefinitionLoader)
        .setWorkflowScheduleEnabled("t1", "WF_JOB_PAUSE_WITH_MISFIRE", false);
  }

  @Test
  @DisplayName("恢复单作业先落库置启用,再按最新描述重新下发调度")
  void shouldPersistEnabledAndScheduleWhenResumingSingleJob() throws Exception {
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_JOB_RESUME"))
        .thenReturn(cronDescriptor("t1", "JOB_RESUME", true));

    facade.resumeByWorkflowCode("t1", "WF_JOB_RESUME");

    var inOrder = inOrder(triggerDefinitionLoader, scheduler);
    inOrder.verify(triggerDefinitionLoader).setWorkflowScheduleEnabled("t1", "WF_JOB_RESUME", true);
    inOrder.verify(triggerDefinitionLoader).loadByWorkflowCode("t1", "WF_JOB_RESUME");
    inOrder.verify(scheduler).scheduleJob(any(JobDetail.class), any(CronTrigger.class));
  }

  @Test
  @DisplayName("cron 表达式非法时跳过该作业,不向 Quartz 注册半成品触发器")
  void shouldSkipInvalidCronExpression() throws Exception {
    TriggerDescriptor descriptor = cronDescriptor("t1", "BAD_CRON", true);
    descriptor.setScheduleExpression("not-a-cron");
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_BAD_CRON")).thenReturn(descriptor);

    facade.registerByWorkflowCode("t1", "WF_BAD_CRON");

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
    assertThat(jobDetail.getKey().getName()).isEqualTo("t1:WF:WF_JOB_FIXED");
    assertThat(jobDetail.getJobDataMap())
        .containsEntry(QuartzLaunchJob.SCHEDULE_TYPE, "FIXED_RATE")
        .containsEntry(QuartzLaunchJob.SCHEDULE_EXPRESSION, "300");

    Trigger trigger = triggerCaptor.getValue();
    assertThat(trigger.getJobDataMap())
        .containsEntry(QuartzLaunchJob.TENANT_ID, "t1")
        .containsEntry(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, 43L);
    assertThat(trigger).isInstanceOf(SimpleTrigger.class);
    SimpleTrigger simpleTrigger = (SimpleTrigger) trigger;
    assertThat(simpleTrigger.getRepeatInterval()).isEqualTo(300_000L); // ms
    assertThat(simpleTrigger.getRepeatCount()).isEqualTo(SimpleTrigger.REPEAT_INDEFINITELY);
    assertThat(simpleTrigger.getMisfireInstruction())
        .isEqualTo(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_EXISTING_COUNT);
  }

  @Test
  @DisplayName("重新注册 FIXED_RATE 前同样先删旧作业,避免周期任务双份执行")
  void shouldReplacePrimaryFixedRateWithoutDeletingQuartzJob() throws Exception {
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_JOB_FR"))
        .thenReturn(fixedRateDescriptor("t1", "JOB_FR", "60", true));
    when(scheduler.checkExists(any(JobKey.class))).thenReturn(true);

    facade.registerByWorkflowCode("t1", "WF_JOB_FR");

    var inOrder = inOrder(scheduler);
    inOrder.verify(scheduler).checkExists(any(JobKey.class));
    inOrder
        .verify(scheduler)
        .scheduleJob(any(JobDetail.class), ArgumentMatchers.anySet(), eq(true));
    verify(scheduler, never()).deleteJob(any(JobKey.class));
  }

  @Test
  @DisplayName("FIXED_RATE 间隔非数字时跳过注册,不让解析异常破坏整批调度")
  void shouldSkipNonNumericFixedRateExpression() throws Exception {
    TriggerDescriptor descriptor = fixedRateDescriptor("t1", "BAD_FR", "not-a-number", true);
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_BAD_FR")).thenReturn(descriptor);

    facade.registerByWorkflowCode("t1", "WF_BAD_FR");

    verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(SimpleTrigger.class));
  }

  @Test
  @DisplayName("FIXED_RATE 间隔为 0 属非法配置,跳过注册以免形成空转触发器")
  void shouldSkipZeroFixedRateInterval() throws Exception {
    TriggerDescriptor descriptor = fixedRateDescriptor("t1", "ZERO_FR", "0", true);
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_ZERO_FR")).thenReturn(descriptor);

    facade.registerByWorkflowCode("t1", "WF_ZERO_FR");

    verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(SimpleTrigger.class));
  }

  @Test
  @DisplayName("FIXED_RATE 间隔为负数属非法配置,跳过注册而非写入调度器")
  void shouldSkipNegativeFixedRateInterval() throws Exception {
    TriggerDescriptor descriptor = fixedRateDescriptor("t1", "NEG_FR", "-10", true);
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_NEG_FR")).thenReturn(descriptor);

    facade.registerByWorkflowCode("t1", "WF_NEG_FR");

    verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(SimpleTrigger.class));
  }

  @Test
  @DisplayName("FIXED_RATE 间隔为空白字符串时同样跳过注册,不写入调度器")
  void shouldSkipBlankFixedRateExpression() throws Exception {
    TriggerDescriptor descriptor = fixedRateDescriptor("t1", "BLANK_FR", "  ", true);
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_BLANK_FR")).thenReturn(descriptor);

    facade.registerByWorkflowCode("t1", "WF_BLANK_FR");

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

  @Test
  @DisplayName("只更新 Workflow 版本而不改 cron 后，下一 fire 使用新版本进入准入")
  void shouldUseUpdatedWorkflowVersionForNextFireWithoutCronChange() throws Exception {
    TriggerDescriptor current = cronDescriptor("t1", "VERSION_ONLY", true);
    current.setCatchUpPolicy(CatchUpPolicyType.NONE.code());
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_VERSION_ONLY")).thenReturn(current);
    when(triggerDefinitionLoader.loadAll()).thenReturn(List.of(current));
    Scheduler actualScheduler = newMemoryScheduler();
    try {
      TriggerSchedulerFacade actualFacade =
          new TriggerSchedulerFacade(triggerDefinitionLoader, actualScheduler);
      actualFacade.reconcileByWorkflowCode("t1", "WF_VERSION_ONLY");
      current.setWorkflowVersion(4);
      TriggerReconciler actualReconciler = new TriggerReconciler(
          triggerDefinitionLoader,
          actualFacade,
          actualScheduler,
          mock(TriggerGracefulShutdown.class),
          mock(TriggerScheduledOccurrencePlanner.class),
          mock(QuartzMisfireRecoveryListener.class));

      actualReconciler.reconcile();

      JobKey jobKey = JobKey.jobKey("t1:WF:WF_VERSION_ONLY", TriggerSchedulerFacade.JOB_GROUP);
      JobDetail storedJob = actualScheduler.getJobDetail(jobKey);
      TriggerKey triggerKey = TriggerKey.triggerKey(jobKey.getName(), jobKey.getGroup());
      Trigger storedTrigger = actualScheduler.getTrigger(triggerKey);
      JobDataMap merged = new JobDataMap(storedJob.getJobDataMap());
      merged.putAll(storedTrigger.getJobDataMap());
      Instant fireTime = Instant.parse("2026-03-27T12:00:00Z");
      Date fireDate = Date.from(fireTime);
      JobExecutionContext fireContext = mock(JobExecutionContext.class);
      when(fireContext.getMergedJobDataMap()).thenReturn(merged);
      when(fireContext.getScheduledFireTime()).thenReturn(fireDate);
      when(fireContext.getFireTime()).thenReturn(fireDate);
      TriggerService admissionService = mock(TriggerService.class);
      TriggerRuntimeProperties properties = new TriggerRuntimeProperties();
      QuartzLaunchJob actualJob = new QuartzLaunchJob(
          admissionService,
          mock(MisfireHandler.class),
          properties,
          actualFacade,
          new SimpleMeterRegistry(),
          mock(ScheduledOccurrenceRecorder.class),
          mock(ReadinessWaitHandoverClient.class));

      actualJob.execute(fireContext);

      ArgumentCaptor<ScheduledTriggerCommand> command =
          ArgumentCaptor.forClass(ScheduledTriggerCommand.class);
      verify(admissionService).launchScheduled(command.capture());
      ScheduledTriggerCommand nextFire = command.getValue();
      Integer admissionVersion = nextFire.descriptor().getWorkflowVersion();
      assertThat(admissionVersion).isEqualTo(4);
      assertThat(nextFire.fireTime()).isEqualTo(fireTime);
      assertThat(nextFire.triggerType()).isEqualTo(TriggerType.SCHEDULED);
    } finally {
      actualScheduler.shutdown(true);
    }
  }

  @Test
  @DisplayName("AUTO 恢复进入 readiness 等待后，对账保留原 fire 的唯一重试")
  void shouldRetainReadinessRetryWhenAutoRecoveryNoLongerHasPendingMisfire() throws Exception {
    TriggerDescriptor current = cronDescriptor("t1", "RECOVERY_READINESS", true);
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_RECOVERY_READINESS"))
        .thenReturn(current);
    when(triggerDefinitionLoader.loadAll()).thenReturn(List.of(current));
    when(triggerDefinitionLoader.loadPendingMisfireDefinitions()).thenReturn(List.of());
    Scheduler actualScheduler = newMemoryScheduler();
    try {
      TriggerSchedulerFacade actualFacade =
          new TriggerSchedulerFacade(triggerDefinitionLoader, actualScheduler);
      actualFacade.reconcileByWorkflowCode("t1", "WF_RECOVERY_READINESS");
      Instant fireTime = Instant.parse("2026-03-27T12:00:00Z");
      LocalDate bizDate = LocalDate.of(2026, 3, 27);
      ScheduledOccurrenceRecorder recorder = mock(ScheduledOccurrenceRecorder.class);
      when(recorder.pendingMisfires("t1", 42L, 100)).thenReturn(List.of(fireTime));
      when(recorder.recordWaitingReadiness(any(), eq(fireTime), eq(bizDate), any(), any()))
          .thenReturn(true);
      TriggerRuntimeProperties properties = new TriggerRuntimeProperties();
      QuartzMisfireRecoveryListener recoveryListener = new QuartzMisfireRecoveryListener(
          () -> actualScheduler, mock(TaskScheduler.class), recorder, properties);
      JobKey primaryKey =
          JobKey.jobKey("t1:WF:WF_RECOVERY_READINESS", TriggerSchedulerFacade.JOB_GROUP);
      recoveryListener.restorePendingMisfires("t1", 42L, primaryKey);
      JobKey recoveryKey = TriggerSchedulerFacade.recoveryJobKey(current);
      JobDetail recoveryJob = actualScheduler.getJobDetail(recoveryKey);
      List<? extends Trigger> recoveryTriggers = actualScheduler.getTriggersOfJob(recoveryKey);
      assertThat(recoveryTriggers).hasSize(1);
      Trigger recoveryTrigger = recoveryTriggers.getFirst();
      JobDataMap merged = new JobDataMap(recoveryJob.getJobDataMap());
      merged.putAll(recoveryTrigger.getJobDataMap());
      Date actualFireDate = Date.from(fireTime.plusSeconds(90));
      when(recorder.readinessWaitWindow(any(), eq(fireTime)))
          .thenReturn(new ReadinessWaitWindow(
              actualFireDate.toInstant(), actualFireDate.toInstant().plusSeconds(7200)));
      JobExecutionContext fireContext = mock(JobExecutionContext.class);
      when(fireContext.getMergedJobDataMap()).thenReturn(merged);
      when(fireContext.getFireTime()).thenReturn(actualFireDate);
      when(fireContext.getJobDetail()).thenReturn(recoveryJob);
      when(fireContext.getScheduler()).thenReturn(actualScheduler);
      TriggerService admissionService = mock(TriggerService.class);
      UpstreamNotReadyException waiting =
          new UpstreamNotReadyException("t1", "RECOVERY_READINESS", "UPSTREAM", bizDate);
      doThrow(waiting).when(admissionService).launchScheduled(any());
      QuartzLaunchJob actualJob = new QuartzLaunchJob(
          admissionService,
          mock(MisfireHandler.class),
          properties,
          actualFacade,
          new SimpleMeterRegistry(),
          recorder,
          mock(ReadinessWaitHandoverClient.class));

      actualJob.execute(fireContext);
      actualScheduler.unscheduleJob(recoveryTrigger.getKey());
      when(recorder.pendingMisfires("t1", 42L, 100)).thenReturn(List.of());
      List<? extends Trigger> readinessBefore = actualScheduler.getTriggersOfJob(recoveryKey);
      assertThat(readinessBefore).hasSize(1);
      Trigger originalRetry = readinessBefore.getFirst();
      TriggerKey originalRetryKey = originalRetry.getKey();
      TriggerReconciler actualReconciler = new TriggerReconciler(
          triggerDefinitionLoader,
          actualFacade,
          actualScheduler,
          mock(TriggerGracefulShutdown.class),
          mock(TriggerScheduledOccurrencePlanner.class),
          recoveryListener);

      actualReconciler.reconcile();

      verify(recorder).recordWaitingReadiness(any(), eq(fireTime), eq(bizDate), any(), any());
      Trigger retainedRetry = actualScheduler.getTrigger(originalRetryKey);
      assertThat(retainedRetry).as("已观察等待仍应保留可执行回调").isNotNull();
      JobDataMap retainedData = retainedRetry.getJobDataMap();
      long retainedFire = retainedData.getLongValue(QuartzLaunchJob.READINESS_ORIGINAL_FIRE_TIME);
      assertThat(retainedFire).isEqualTo(fireTime.toEpochMilli());
    } finally {
      actualScheduler.shutdown(true);
    }
  }

  @Test
  @DisplayName("只更新固定频率元数据时保留原相位和下一次fire")
  void shouldPreserveFixedRatePhaseWhenOnlyMetadataChanges() throws Exception {
    TriggerDescriptor current = fixedRateDescriptor("t1", "PHASE", "60", true);
    current.setWorkflowVersion(3);
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_PHASE")).thenReturn(current);
    Scheduler actual = newMemoryScheduler();
    try {
      TriggerSchedulerFacade service = new TriggerSchedulerFacade(triggerDefinitionLoader, actual);
      service.reconcileByWorkflowCode("t1", "WF_PHASE");
      TriggerKey key = TriggerKey.triggerKey("t1:WF:WF_PHASE", TriggerSchedulerFacade.JOB_GROUP);
      Trigger initial = actual.getTrigger(key);
      Date phase = Date.from(Instant.parse("2099-01-01T00:00:17Z"));
      Trigger pinned = initial.getTriggerBuilder().startAt(phase).build();
      actual.rescheduleJob(key, pinned);
      Date nextFire = actual.getTrigger(key).getNextFireTime();
      current.setWorkflowVersion(4);

      service.reconcileByWorkflowCode("t1", "WF_PHASE");

      Trigger retained = actual.getTrigger(key);
      assertThat(retained.getStartTime()).isEqualTo(phase);
      assertThat(retained.getNextFireTime()).isEqualTo(nextFire);
      JobDetail definition = actual.getJobDetail(retained.getJobKey());
      assertThat(definition.getJobDataMap().getIntValue(QuartzLaunchJob.WORKFLOW_VERSION))
          .isEqualTo(4);
    } finally {
      actual.shutdown(true);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("定义更新不能删除等待回调，也不能把新依赖注入原null或空依赖")
  void shouldKeepOldReadinessDescriptorWhenDefinitionChanges(boolean emptyDependencies)
      throws Exception {
    TriggerDescriptor current = cronDescriptor("t1", "FROZEN", true);
    current.setDependsOnJobCodes(emptyDependencies ? List.of() : null);
    current.setDependsOnJobCode("LEGACY");
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_FROZEN")).thenReturn(current);
    Scheduler actual = newMemoryScheduler();
    try {
      TriggerSchedulerFacade service = new TriggerSchedulerFacade(triggerDefinitionLoader, actual);
      service.reconcileByWorkflowCode("t1", "WF_FROZEN");
      JobKey jobKey = JobKey.jobKey("t1:WF:WF_FROZEN", TriggerSchedulerFacade.JOB_GROUP);
      Instant originalFire = Instant.parse("2026-03-27T12:00:00Z");
      JobDataMap original = new JobDataMap(actual.getJobDetail(jobKey).getJobDataMap());
      original.put(QuartzLaunchJob.READINESS_ORIGINAL_FIRE_TIME, originalFire.toEpochMilli());
      original.put(
          QuartzLaunchJob.READINESS_DEFERRED_SINCE, originalFire.plusSeconds(90).toEpochMilli());
      original.put(QuartzLaunchJob.READINESS_TRIGGER_TYPE, TriggerType.CATCH_UP.name());
      Trigger retry = TriggerBuilder.newTrigger()
          .withIdentity("frozen-readiness", TriggerSchedulerFacade.JOB_GROUP)
          .forJob(jobKey)
          .usingJobData(original)
          .startAt(Date.from(Instant.parse("2099-01-01T00:00:00Z")))
          .build();
      actual.scheduleJob(retry);
      current.setWorkflowVersion(4);
      current.setCalendarCode("NEW_CAL");
      current.setDependsOnJobCode("NEW_LEGACY");
      current.setDependsOnJobCodes(List.of("NEW_DEPENDENCY"));
      if (emptyDependencies) current.setScheduleExpression("0 30 1 * * ?");

      service.reconcileByWorkflowCode("t1", "WF_FROZEN");

      Trigger retained = actual.getTrigger(retry.getKey());
      assertThat(retained).isNotNull();
      ScheduledTriggerCommand fired =
          captureScheduledFire(actual, service, retained, originalFire.plusSeconds(100));
      assertThat(fired.descriptor().getWorkflowVersion()).isEqualTo(3);
      assertThat(fired.descriptor().getCalendarCode()).isEqualTo("BIZ_CAL");
      assertThat(fired.descriptor().getDependsOnJobCode()).isEqualTo("LEGACY");
      if (emptyDependencies)
        assertThat(fired.descriptor().getDependsOnJobCodes()).isEmpty();
      else assertThat(fired.descriptor().getDependsOnJobCodes()).isNull();
      assertThat(fired.fireTime()).isEqualTo(originalFire);
      assertThat(retained.getJobDataMap().getLongValue(QuartzLaunchJob.READINESS_DEFERRED_SINCE))
          .isEqualTo(originalFire.plusSeconds(90).toEpochMilli());
    } finally {
      actual.shutdown(true);
    }
  }

  @Test
  @DisplayName("同一恢复Job承载两代fire时，各恢复回调保留自己的版本与依赖")
  void shouldKeepRecoveryMetadataPerFireAcrossDefinitionUpdates() throws Exception {
    TriggerDescriptor current = cronDescriptor("t1", "RECOVERY_VERSION", true);
    when(triggerDefinitionLoader.loadByWorkflowCode("t1", "WF_RECOVERY_VERSION"))
        .thenReturn(current);
    ScheduledOccurrenceRecorder recorder = mock(ScheduledOccurrenceRecorder.class);
    Instant oldFire = Instant.parse("2026-03-27T12:00:00Z");
    Instant newFire = oldFire.plusSeconds(60);
    when(recorder.pendingMisfires("t1", 42L, 100))
        .thenReturn(List.of(oldFire))
        .thenReturn(List.of(newFire));
    Scheduler actual = newMemoryScheduler();
    try {
      TriggerSchedulerFacade service = new TriggerSchedulerFacade(triggerDefinitionLoader, actual);
      service.reconcileByWorkflowCode("t1", "WF_RECOVERY_VERSION");
      JobKey primary = JobKey.jobKey("t1:WF:WF_RECOVERY_VERSION", TriggerSchedulerFacade.JOB_GROUP);
      QuartzMisfireRecoveryListener listener = new QuartzMisfireRecoveryListener(
          () -> actual, mock(TaskScheduler.class), recorder, new TriggerRuntimeProperties());
      listener.restorePendingMisfires("t1", 42L, primary);
      current.setWorkflowVersion(4);
      current.setDependsOnJobCodes(List.of("NEW_DEPENDENCY"));
      service.reconcileByWorkflowCode("t1", "WF_RECOVERY_VERSION");
      listener.restorePendingMisfires("t1", 42L, primary);

      Trigger oldRecovery = actual.getTrigger(TriggerKey.triggerKey(
          "misfire-recovery-42-" + oldFire.toEpochMilli(),
          QuartzMisfireRecoveryListener.RECOVERY_GROUP));
      Trigger newRecovery = actual.getTrigger(TriggerKey.triggerKey(
          "misfire-recovery-42-" + newFire.toEpochMilli(),
          QuartzMisfireRecoveryListener.RECOVERY_GROUP));
      ScheduledTriggerCommand oldCommand =
          captureScheduledFire(actual, service, oldRecovery, newFire.plusSeconds(90));
      ScheduledTriggerCommand newCommand =
          captureScheduledFire(actual, service, newRecovery, newFire.plusSeconds(90));
      assertThat(oldCommand.descriptor().getWorkflowVersion()).isEqualTo(3);
      assertThat(oldCommand.descriptor().getDependsOnJobCodes()).isNull();
      assertThat(newCommand.descriptor().getWorkflowVersion()).isEqualTo(4);
      assertThat(newCommand.descriptor().getDependsOnJobCodes()).containsExactly("NEW_DEPENDENCY");
      assertThat(oldCommand.fireTime()).isEqualTo(oldFire);
      assertThat(newCommand.fireTime()).isEqualTo(newFire);
    } finally {
      actual.shutdown(true);
    }
  }

  private ScheduledTriggerCommand captureScheduledFire(
      Scheduler actual, TriggerSchedulerFacade service, Trigger trigger, Instant actualFire)
      throws Exception {
    JobDetail job = actual.getJobDetail(trigger.getJobKey());
    JobDataMap merged = new JobDataMap(job.getJobDataMap());
    merged.putAll(trigger.getJobDataMap());
    JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(merged);
    when(context.getFireTime()).thenReturn(Date.from(actualFire));
    TriggerService admission = mock(TriggerService.class);
    QuartzLaunchJob launch = new QuartzLaunchJob(
        admission,
        mock(MisfireHandler.class),
        new TriggerRuntimeProperties(),
        service,
        new SimpleMeterRegistry(),
        mock(ScheduledOccurrenceRecorder.class),
        mock(ReadinessWaitHandoverClient.class));
    launch.execute(context);
    ArgumentCaptor<ScheduledTriggerCommand> command =
        ArgumentCaptor.forClass(ScheduledTriggerCommand.class);
    verify(admission).launchScheduled(command.capture());
    return command.getValue();
  }

  private Scheduler newMemoryScheduler() throws Exception {
    Properties properties = new Properties();
    properties.setProperty("org.quartz.scheduler.instanceName", "b192-" + UUID.randomUUID());
    properties.setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", "true");
    properties.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
    properties.setProperty("org.quartz.threadPool.threadCount", "1");
    properties.setProperty("org.quartz.threadPool.makeThreadsDaemons", "true");
    properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
    StdSchedulerFactory factory = new StdSchedulerFactory(properties);
    return factory.getScheduler();
  }

  // ─── helpers ──────────────────────────────────────────────────────────────────

  private TriggerDescriptor cronDescriptor(String tenantId, String jobCode, boolean enabled) {
    TriggerDescriptor d = new TriggerDescriptor();
    d.setTenantId(tenantId);
    d.setJobCode(jobCode);
    d.setWorkflowCode("WF_" + jobCode);
    d.setWorkflowDefinitionId(42L);
    d.setWorkflowCode("WF_" + jobCode);
    d.setWorkflowVersion(3);
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
    d.setWorkflowCode("WF_" + jobCode);
    d.setWorkflowDefinitionId(43L);
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
