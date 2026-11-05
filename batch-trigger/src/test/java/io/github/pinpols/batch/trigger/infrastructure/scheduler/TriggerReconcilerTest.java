package io.github.pinpols.batch.trigger.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.TriggerRegistrationService;
import io.github.pinpols.batch.trigger.infrastructure.QuartzMisfireRecoveryListener;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.infrastructure.TriggerSchedulerFacade;
import io.github.pinpols.batch.trigger.service.TriggerScheduledOccurrencePlanner;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleTrigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;

@ExtendWith(MockitoExtension.class)
@DisplayName("Trigger 对账器:DB 与 Quartz 的注册、清理与表达式漂移处理,及排空期与脏 JobKey 容错")
class TriggerReconcilerTest {

  @Mock
  private TriggerDefinitionLoader loader;

  @Mock
  private TriggerRegistrationService registration;

  @Mock
  private Scheduler scheduler;

  @Mock
  private TriggerGracefulShutdown gracefulShutdown;

  @Mock
  private TriggerScheduledOccurrencePlanner occurrencePlanner;

  @Mock
  private QuartzMisfireRecoveryListener misfireRecoveryListener;

  @InjectMocks
  private TriggerReconciler reconciler;

  @BeforeEach
  void resetDrainState() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
  }

  @Test
  @DisplayName("DB 有启用描述而 Quartz 无对应作业时补注册,且不误清理")
  void dbOnly_registersMissingQuartzJob() throws Exception {
    TriggerDescriptor enabled = enabledDescriptor("t1", "JOB_A");
    when(loader.loadAll()).thenReturn(List.of(enabled));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any())).thenReturn(Set.of());

    reconciler.reconcile();

    verify(registration).reconcileByWorkflowCode("t1", "WF-JOB_A");
    verify(registration, never()).registerByWorkflowCode(any(), any());
    verify(registration, never()).removeQuartzJob(any(), any());
  }

  @Test
  @DisplayName("应用启动就绪时立即对账,不等待首个 30 秒周期")
  void applicationReady_reconcilesImmediately() throws Exception {
    TriggerDescriptor enabled = enabledDescriptor("t1", "JOB_STARTUP");
    when(loader.loadAll()).thenReturn(List.of(enabled));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any())).thenReturn(Set.of());

    reconciler.onApplicationReady();

    verify(registration).reconcileByWorkflowCode("t1", "WF-JOB_STARTUP");
    verify(registration, never()).registerByWorkflowCode(any(), any());
  }

  @Test
  @DisplayName("Quartz 存在 DB 已无的孤儿 Workflow 时按稳定身份拆解清理")
  void quartzOnly_unregistersOrphanedJob() throws Exception {
    when(loader.loadAll()).thenReturn(List.of());
    JobKey orphan = JobKey.jobKey("t1:WF:WF-STALE", TriggerSchedulerFacade.JOB_GROUP);
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(orphan));

    reconciler.reconcile();

    verify(registration).removeQuartzJob("t1", "WF:WF-STALE");
    verify(registration, never()).registerByWorkflowCode(any(), any());
    verify(occurrencePlanner)
        .supersedeFuturePlans(ArgumentMatchers.eq("t1"), ArgumentMatchers.eq("WF-STALE"), any());
  }

  @Test
  @DisplayName("DB 与 Quartz 完全一致时不产生任何注册或清理动作")
  void dbAndQuartzAligned_noChanges() throws Exception {
    TriggerDescriptor enabled = enabledDescriptor("t1", "JOB_A");
    JobKey existing = JobKey.jobKey("t1:WF:WF-JOB_A", TriggerSchedulerFacade.JOB_GROUP);
    when(loader.loadAll()).thenReturn(List.of(enabled));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(existing));

    reconciler.reconcile();

    verify(registration, never()).registerByWorkflowCode(any(), any());
    verify(registration, never()).removeQuartzJob(any(), any());
  }

  @Test
  @DisplayName("已对齐的 Quartz 主 Trigger 也周期物化预期 occurrence")
  void alignedSchedule_materializesUpcomingOccurrences() throws Exception {
    TriggerDescriptor enabled = enabledDescriptor("t1", "JOB_A");
    JobKey existing = JobKey.jobKey("t1:WF:WF-JOB_A", TriggerSchedulerFacade.JOB_GROUP);
    CronTrigger primary = TriggerBuilder.newTrigger()
        .withIdentity(existing.getName(), existing.getGroup())
        .forJob(existing)
        .withSchedule(
            CronScheduleBuilder.cronSchedule("0 0 1 * * ?").inTimeZone(TimeZone.getTimeZone("UTC")))
        .build();
    when(loader.loadAll()).thenReturn(List.of(enabled));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(existing));
    doReturn(List.of(primary)).when(scheduler).getTriggersOfJob(existing);

    reconciler.reconcile();

    verify(occurrencePlanner).materialize(any(TriggerDescriptor.class), any(), any());
    verify(misfireRecoveryListener).restorePendingMisfires(enabled, existing);
  }

  @Test
  @DisplayName("DB 已停用的作业若仍留在 Quartz 中,对账应将其清理")
  void disabledDescriptor_unregistersExistingQuartzJob() throws Exception {
    TriggerDescriptor disabled = disabledDescriptor("t1", "JOB_A");
    JobKey existing = JobKey.jobKey("t1:WF:WF-JOB_A", TriggerSchedulerFacade.JOB_GROUP);
    when(loader.loadAll()).thenReturn(List.of(disabled));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(existing));

    reconciler.reconcile();

    verify(registration).removeQuartzJob("t1", "WF:WF-JOB_A");
    verify(registration, never()).registerByWorkflowCode(any(), any());
  }

  @Test
  @DisplayName("清理已停用主 Job 后再恢复既有 misfire,避免 Quartz 删除绑定主 Job 的恢复触发器")
  void disabledWorkflow_removesPrimaryBeforeRestoringPendingMisfire() throws Exception {
    TriggerDescriptor disabled = disabledDescriptor("t1", "JOB_A");
    disabled.setWorkflowDefinitionId(11L);
    JobKey primary = JobKey.jobKey("t1:WF:WF-JOB_A", TriggerSchedulerFacade.JOB_GROUP);
    JobKey recovery = JobKey.jobKey("t1:RECOVERY:11", TriggerSchedulerFacade.JOB_GROUP);
    when(loader.loadAll()).thenReturn(List.of(disabled));
    when(loader.loadPendingMisfireDefinitions()).thenReturn(List.of(disabled));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(primary));

    reconciler.reconcile();

    var ordered = inOrder(registration, misfireRecoveryListener);
    ordered.verify(registration).registerRecoveryJob(disabled);
    ordered.verify(registration).removeQuartzJob("t1", "WF:WF-JOB_A");
    ordered.verify(misfireRecoveryListener).restorePendingMisfires("t1", 11L, recovery);
  }

  @Test
  @DisplayName("优雅排空期间整轮对账直接跳过,不读 DB 也不碰调度器")
  void drainingState_skipsReconcile() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    reconciler.reconcile();

    verify(loader, never()).loadAll();
    verify(registration, never()).registerByWorkflowCode(any(), any());
    verify(registration, never()).removeQuartzJob(any(), any());
  }

  @Test
  @DisplayName("DB 与 Quartz 的 cron 表达式不一致时触发重新注册,以 DB 为准")
  void scheduleDrift_triggersReRegister() throws Exception {
    TriggerDescriptor descriptor = enabledDescriptor("t1", "JOB_A");
    descriptor.setScheduleType("CRON");
    descriptor.setScheduleExpression("0 0 2 * * ?"); // DB expects every day 02:00 (Quartz)
    descriptor.setTimezone("Asia/Shanghai");
    JobKey existing = JobKey.jobKey("t1:WF:WF-JOB_A", TriggerSchedulerFacade.JOB_GROUP);

    CronTrigger quartzTrigger = mock(CronTrigger.class);
    when(quartzTrigger.getKey())
        .thenReturn(TriggerKey.triggerKey("t1:WF:WF-JOB_A", TriggerSchedulerFacade.JOB_GROUP));
    when(quartzTrigger.getCronExpression()).thenReturn("0 0 3 * * ?"); // Quartz still has old
    // timezone stub 被 short-circuit 跳过（cron 不同已命中 drift），故不 stub 以避免 strict mode 报错

    when(loader.loadAll()).thenReturn(List.of(descriptor));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(existing));
    doReturn(List.of(quartzTrigger)).when(scheduler).getTriggersOfJob(existing);

    reconciler.reconcile();

    verify(registration).reconcileByWorkflowCode("t1", "WF-JOB_A");
    verify(registration, never()).registerByWorkflowCode(any(), any());
    verify(registration, never()).removeQuartzJob(any(), any());
  }

  @Test
  @DisplayName("作业挂有 misfire 恢复辅助触发器时,不得拿它比对主调度而误判漂移")
  void auxiliaryRecoveryTrigger_doesNotCauseScheduleDrift() throws Exception {
    TriggerDescriptor descriptor = enabledDescriptor("t1", "JOB_A");
    descriptor.setScheduleType("CRON");
    descriptor.setScheduleExpression("0 0 2 * * ?");
    descriptor.setTimezone("Asia/Shanghai");
    JobKey existing = JobKey.jobKey("t1:WF:WF-JOB_A", TriggerSchedulerFacade.JOB_GROUP);

    SimpleTrigger recoveryTrigger = mock(SimpleTrigger.class);
    when(recoveryTrigger.getKey())
        .thenReturn(TriggerKey.triggerKey("misfire-recovery-1", "batch-trigger-recovery"));
    CronTrigger primaryTrigger = mock(CronTrigger.class);
    when(primaryTrigger.getKey())
        .thenReturn(TriggerKey.triggerKey("t1:WF:WF-JOB_A", TriggerSchedulerFacade.JOB_GROUP));
    when(primaryTrigger.getCronExpression()).thenReturn("0 0 2 * * ?");
    when(primaryTrigger.getTimeZone()).thenReturn(TimeZone.getTimeZone("Asia/Shanghai"));

    when(loader.loadAll()).thenReturn(List.of(descriptor));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(existing));
    doReturn(List.of(recoveryTrigger, primaryTrigger)).when(scheduler).getTriggersOfJob(existing);

    reconciler.reconcile();

    verify(registration, never()).registerByWorkflowCode(any(), any());
    verify(registration, never()).removeQuartzJob(any(), any());
  }

  @Test
  @DisplayName("JobKey 缺少租户分隔符时记录告警并跳过,不按错误租户清理")
  void malformedJobKey_logsAndSkips() throws Exception {
    when(loader.loadAll()).thenReturn(List.of());
    JobKey malformed = JobKey.jobKey("nocolon", TriggerSchedulerFacade.JOB_GROUP);
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(malformed));

    reconciler.reconcile();

    verify(registration, never()).removeQuartzJob(any(), any());
  }

  @Test
  @DisplayName("坏定义不阻塞后续健康定义注册、计划与恢复,也不误删既有调度")
  void shouldContinueHealthyDefinition_whenEarlierRegistrationIsInvalid() throws Exception {
    TriggerDescriptor invalid = enabledDescriptor("t1", "BAD");
    invalid.setScheduleType("CRON");
    invalid.setScheduleExpression("0 0 1 * * ?");
    TriggerDescriptor healthy = enabledDescriptor("t1", "HEALTHY");
    JobKey invalidKey = JobKey.jobKey("t1:WF:WF-BAD", TriggerSchedulerFacade.JOB_GROUP);
    JobKey healthyKey = JobKey.jobKey("t1:WF:WF-HEALTHY", TriggerSchedulerFacade.JOB_GROUP);
    CronTrigger oldInvalid = TriggerBuilder.newTrigger()
        .withIdentity(invalidKey.getName(), invalidKey.getGroup())
        .forJob(invalidKey)
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 2 * * ?"))
        .build();
    CronTrigger healthyTrigger = TriggerBuilder.newTrigger()
        .withIdentity(healthyKey.getName(), healthyKey.getGroup())
        .forJob(healthyKey)
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 1 * * ?"))
        .build();
    when(loader.loadAll()).thenReturn(List.of(invalid, healthy));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(invalidKey));
    doReturn(List.of(oldInvalid)).when(scheduler).getTriggersOfJob(invalidKey);
    doReturn(List.of(healthyTrigger)).when(scheduler).getTriggersOfJob(healthyKey);
    doThrow(new IllegalArgumentException("invalid timezone"))
        .when(registration)
        .reconcileByWorkflowCode("t1", "WF-BAD");

    reconciler.reconcile();

    verify(registration).reconcileByWorkflowCode("t1", "WF-HEALTHY");
    verify(occurrencePlanner).materialize(ArgumentMatchers.eq(healthy), any(), any());
    verify(misfireRecoveryListener).restorePendingMisfires(healthy, healthyKey);
    verify(occurrencePlanner, never()).materialize(ArgumentMatchers.eq(invalid), any(), any());
    verify(misfireRecoveryListener, never()).restorePendingMisfires(invalid, invalidKey);
    verify(registration, never()).removeQuartzJob(any(), any());
  }

  @Test
  @DisplayName("全局注册基础设施失败中止本轮,不继续注册或清理")
  void shouldAbortPass_whenRegistrationInfrastructureFails() throws Exception {
    TriggerDescriptor first = enabledDescriptor("t1", "FIRST");
    TriggerDescriptor next = enabledDescriptor("t1", "NEXT");
    when(loader.loadAll()).thenReturn(List.of(first, next));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(JobKey.jobKey("t1:WF:ORPHAN", TriggerSchedulerFacade.JOB_GROUP)));
    doThrow(new IllegalStateException("Quartz unavailable"))
        .when(registration)
        .reconcileByWorkflowCode("t1", "WF-FIRST");

    reconciler.reconcile();

    verify(registration, never()).reconcileByWorkflowCode("t1", "WF-NEXT");
    verify(registration, never()).removeQuartzJob(any(), any());
    verify(occurrencePlanner, never()).materialize(any(), any(), any());
  }

  @Test
  @DisplayName("定义身份缺失时继续健康注册,但不依据不完整身份集合清理")
  void shouldPreserveQuartzJobs_whenDefinitionIdentityIsMissing() throws Exception {
    TriggerDescriptor invalid = enabledDescriptor("t1", "BAD");
    invalid.setWorkflowCode(null);
    TriggerDescriptor healthy = enabledDescriptor("t1", "HEALTHY");
    when(loader.loadAll()).thenReturn(List.of(invalid, healthy));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(JobKey.jobKey("t1:WF:EXISTING", TriggerSchedulerFacade.JOB_GROUP)));

    reconciler.reconcile();

    verify(registration).reconcileByWorkflowCode("t1", "WF-HEALTHY");
    verify(registration, never()).removeQuartzJob(any(), any());
  }

  @Test
  @DisplayName("恢复定义校验失败不阻塞后续恢复,且保留其已有恢复任务")
  void shouldRestoreHealthyPendingMisfire_whenEarlierRecoveryDefinitionIsInvalid()
      throws Exception {
    TriggerDescriptor invalid = enabledDescriptor("t1", "BAD");
    TriggerDescriptor healthy = enabledDescriptor("t1", "HEALTHY");
    healthy.setWorkflowDefinitionId(12L);
    JobKey invalidKey = JobKey.jobKey("t1:RECOVERY:11", TriggerSchedulerFacade.JOB_GROUP);
    JobKey healthyKey = JobKey.jobKey("t1:RECOVERY:12", TriggerSchedulerFacade.JOB_GROUP);
    when(loader.loadAll()).thenReturn(List.of());
    when(loader.loadPendingMisfireDefinitions()).thenReturn(List.of(invalid, healthy));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(invalidKey));
    doThrow(new IllegalArgumentException("invalid recovery definition"))
        .when(registration)
        .registerRecoveryJob(invalid);

    reconciler.reconcile();

    verify(registration).registerRecoveryJob(healthy);
    verify(misfireRecoveryListener).restorePendingMisfires("t1", 12L, healthyKey);
    verify(misfireRecoveryListener, never()).restorePendingMisfires("t1", 11L, invalidKey);
    verify(registration, never()).removeQuartzJob(any(), any());
  }

  @Test
  @DisplayName("单条恢复载荷非法不阻塞后续已注册恢复任务")
  void shouldContinuePendingRecovery_whenEarlierRestoreRejectsDefinition() throws Exception {
    TriggerDescriptor invalid = enabledDescriptor("t1", "BAD");
    TriggerDescriptor healthy = enabledDescriptor("t1", "HEALTHY");
    healthy.setWorkflowDefinitionId(12L);
    JobKey invalidKey = JobKey.jobKey("t1:RECOVERY:11", TriggerSchedulerFacade.JOB_GROUP);
    JobKey healthyKey = JobKey.jobKey("t1:RECOVERY:12", TriggerSchedulerFacade.JOB_GROUP);
    when(loader.loadAll()).thenReturn(List.of());
    when(loader.loadPendingMisfireDefinitions()).thenReturn(List.of(invalid, healthy));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any())).thenReturn(Set.of());
    doThrow(new IllegalArgumentException("invalid recovery payload"))
        .when(misfireRecoveryListener)
        .restorePendingMisfires("t1", 11L, invalidKey);

    reconciler.reconcile();

    verify(misfireRecoveryListener).restorePendingMisfires("t1", 12L, healthyKey);
  }

  private TriggerDescriptor enabledDescriptor(String tenantId, String jobCode) {
    TriggerDescriptor d = new TriggerDescriptor();
    d.setTenantId(tenantId);
    d.setJobCode(jobCode);
    d.setWorkflowDefinitionId(11L);
    d.setWorkflowCode("WF-" + jobCode);
    d.setEnabled(true);
    return d;
  }

  private TriggerDescriptor disabledDescriptor(String tenantId, String jobCode) {
    TriggerDescriptor d = new TriggerDescriptor();
    d.setTenantId(tenantId);
    d.setJobCode(jobCode);
    d.setWorkflowCode("WF-" + jobCode);
    d.setEnabled(false);
    return d;
  }
}
