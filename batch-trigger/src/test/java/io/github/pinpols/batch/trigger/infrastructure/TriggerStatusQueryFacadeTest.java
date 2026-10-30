package io.github.pinpols.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.impl.matchers.GroupMatcher;

@DisplayName("触发器状态查询：按租户隔离 Workflow 调度")
class TriggerStatusQueryFacadeTest {

  @Test
  @DisplayName("指定租户时只读取匹配租户的作业详情")
  void tenant_scope_loads_only_matching_tenant_job_details() throws Exception {
    Scheduler scheduler = mock(Scheduler.class);
    JobKey tenantA = JobKey.jobKey("tenant-a:WF:FLOW_A", TriggerSchedulerFacade.JOB_GROUP);
    JobKey tenantB = JobKey.jobKey("tenant-b:WF:FLOW_B", TriggerSchedulerFacade.JOB_GROUP);
    JobKey legacyJobIdentity = JobKey.jobKey("tenant-a:JOB_A", TriggerSchedulerFacade.JOB_GROUP);
    JobDetail detail = mock(JobDetail.class);
    when(detail.getJobDataMap()).thenReturn(new JobDataMap());
    when(scheduler.getJobKeys(GroupMatcher.jobGroupEquals(TriggerSchedulerFacade.JOB_GROUP)))
        .thenReturn(Set.of(tenantA, tenantB, legacyJobIdentity));
    when(scheduler.getJobDetail(tenantA)).thenReturn(detail);
    when(scheduler.getTriggersOfJob(tenantA)).thenReturn(List.of());

    var results = new TriggerStatusQueryFacade(scheduler).listRegisteredTriggers("tenant-a");

    assertThat(results).hasSize(1);
    assertThat(results.get(0).tenantId()).isEqualTo("tenant-a");
    assertThat(results.get(0).workflowCode()).isEqualTo("FLOW_A");
    verify(scheduler, never()).getJobDetail(tenantB);
    verify(scheduler, never()).getJobDetail(legacyJobIdentity);
  }

  @Test
  @DisplayName("未指定租户时保留全局查询行为")
  void missing_tenant_scope_keeps_global_listing() throws Exception {
    Scheduler scheduler = mock(Scheduler.class);
    JobKey legacyJobIdentity = JobKey.jobKey("tenant-a:JOB_A", TriggerSchedulerFacade.JOB_GROUP);
    when(scheduler.getJobKeys(GroupMatcher.jobGroupEquals(TriggerSchedulerFacade.JOB_GROUP)))
        .thenReturn(Set.of(legacyJobIdentity));

    assertThat(new TriggerStatusQueryFacade(scheduler).listRegisteredTriggers(null))
        .isEmpty();
    verify(scheduler, never()).getJobDetail(legacyJobIdentity);
  }

  @Test
  @DisplayName("Workflow Quartz 身份返回 WorkflowCode 而不伪造 JobCode")
  void workflow_identity_is_returned_as_workflow_code() throws Exception {
    Scheduler scheduler = mock(Scheduler.class);
    JobKey workflowKey = JobKey.jobKey("tenant-a:WF:FLOW_A", TriggerSchedulerFacade.JOB_GROUP);
    JobDetail detail = mock(JobDetail.class);
    JobDataMap data = new JobDataMap();
    data.put(QuartzLaunchJob.WORKFLOW_CODE, "FLOW_A");
    data.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, 71L);
    when(detail.getJobDataMap()).thenReturn(data);
    when(scheduler.getJobKeys(GroupMatcher.jobGroupEquals(TriggerSchedulerFacade.JOB_GROUP)))
        .thenReturn(Set.of(workflowKey));
    when(scheduler.getJobDetail(workflowKey)).thenReturn(detail);
    when(scheduler.getTriggersOfJob(workflowKey)).thenReturn(List.of());

    var result = new TriggerStatusQueryFacade(scheduler).listRegisteredTriggers("tenant-a");

    assertThat(result).singleElement().satisfies(info -> {
      assertThat(info.tenantId()).isEqualTo("tenant-a");
      assertThat(info.workflowCode()).isEqualTo("FLOW_A");
      assertThat(info.workflowDefinitionId()).isEqualTo(71L);
      assertThat(info.jobCode()).isNull();
    });
  }
}
