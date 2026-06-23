package io.github.pinpols.batch.trigger.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.TriggerRegistrationService;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.infrastructure.TriggerSchedulerFacade;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.impl.matchers.GroupMatcher;

@ExtendWith(MockitoExtension.class)
class TriggerReconcilerTest {

  @Mock private TriggerDefinitionLoader loader;
  @Mock private TriggerRegistrationService registration;
  @Mock private Scheduler scheduler;
  @Mock private TriggerGracefulShutdown gracefulShutdown;

  @InjectMocks private TriggerReconciler reconciler;

  @BeforeEach
  void resetDrainState() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
  }

  @Test
  void dbOnly_registersMissingQuartzJob() throws Exception {
    TriggerDescriptor enabled = enabledDescriptor("t1", "JOB_A");
    when(loader.loadAll()).thenReturn(List.of(enabled));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any())).thenReturn(Set.of());

    reconciler.reconcile();

    verify(registration).registerByJobCode("t1", "JOB_A");
    verify(registration, never()).unregisterByJobCode(any(), any());
  }

  @Test
  void quartzOnly_unregistersOrphanedJob() throws Exception {
    when(loader.loadAll()).thenReturn(List.of());
    JobKey orphan = JobKey.jobKey("t1:STALE_JOB", TriggerSchedulerFacade.JOB_GROUP);
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(orphan));

    reconciler.reconcile();

    verify(registration).unregisterByJobCode("t1", "STALE_JOB");
    verify(registration, never()).registerByJobCode(any(), any());
  }

  @Test
  void dbAndQuartzAligned_noChanges() throws Exception {
    TriggerDescriptor enabled = enabledDescriptor("t1", "JOB_A");
    JobKey existing = JobKey.jobKey("t1:JOB_A", TriggerSchedulerFacade.JOB_GROUP);
    when(loader.loadAll()).thenReturn(List.of(enabled));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(existing));

    reconciler.reconcile();

    verify(registration, never()).registerByJobCode(any(), any());
    verify(registration, never()).unregisterByJobCode(any(), any());
  }

  @Test
  void disabledDescriptor_unregistersExistingQuartzJob() throws Exception {
    TriggerDescriptor disabled = disabledDescriptor("t1", "JOB_A");
    JobKey existing = JobKey.jobKey("t1:JOB_A", TriggerSchedulerFacade.JOB_GROUP);
    when(loader.loadAll()).thenReturn(List.of(disabled));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(existing));

    reconciler.reconcile();

    verify(registration).unregisterByJobCode("t1", "JOB_A");
    verify(registration, never()).registerByJobCode(any(), any());
  }

  @Test
  void drainingState_skipsReconcile() throws Exception {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    reconciler.reconcile();

    verify(loader, never()).loadAll();
    verify(registration, never()).registerByJobCode(any(), any());
    verify(registration, never()).unregisterByJobCode(any(), any());
  }

  @Test
  void scheduleDrift_triggersReRegister() throws Exception {
    TriggerDescriptor descriptor = enabledDescriptor("t1", "JOB_A");
    descriptor.setScheduleType("CRON");
    descriptor.setScheduleExpression("0 0 2 * * ?"); // DB expects every day 02:00 (Quartz)
    descriptor.setTimezone("Asia/Shanghai");
    JobKey existing = JobKey.jobKey("t1:JOB_A", TriggerSchedulerFacade.JOB_GROUP);

    CronTrigger quartzTrigger = mock(CronTrigger.class);
    when(quartzTrigger.getCronExpression()).thenReturn("0 0 3 * * ?"); // Quartz still has old
    // timezone stub 被 short-circuit 跳过（cron 不同已命中 drift），故不 stub 以避免 strict mode 报错

    when(loader.loadAll()).thenReturn(List.of(descriptor));
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(existing));
    doReturn(List.of(quartzTrigger)).when(scheduler).getTriggersOfJob(existing);

    reconciler.reconcile();

    verify(registration).registerByJobCode("t1", "JOB_A");
    verify(registration, never()).unregisterByJobCode(any(), any());
  }

  @Test
  void malformedJobKey_logsAndSkips() throws Exception {
    when(loader.loadAll()).thenReturn(List.of());
    JobKey malformed = JobKey.jobKey("nocolon", TriggerSchedulerFacade.JOB_GROUP);
    when(scheduler.getJobKeys(ArgumentMatchers.<GroupMatcher<JobKey>>any()))
        .thenReturn(Set.of(malformed));

    reconciler.reconcile();

    verify(registration, never()).unregisterByJobCode(any(), any());
  }

  private TriggerDescriptor enabledDescriptor(String tenantId, String jobCode) {
    TriggerDescriptor d = new TriggerDescriptor();
    d.setTenantId(tenantId);
    d.setJobCode(jobCode);
    d.setEnabled(true);
    return d;
  }

  private TriggerDescriptor disabledDescriptor(String tenantId, String jobCode) {
    TriggerDescriptor d = new TriggerDescriptor();
    d.setTenantId(tenantId);
    d.setJobCode(jobCode);
    d.setEnabled(false);
    return d;
  }
}
