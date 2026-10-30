package io.github.pinpols.batch.console.infrastructure.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.ActiveCalendarRow;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowNodeUpsertParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

@DisplayName("受管单作业 Workflow Provisioner")
class DefaultSingleJobWorkflowProvisionerTest {

  @Test
  @DisplayName("初始化单作业 Workflow 时将 Job 的执行窗口迁移到受管 JOB 节点")
  void shouldCopyExecutionWindowToManagedJobNode() {
    WorkflowDefinitionMapper definitionMapper = Mockito.mock(WorkflowDefinitionMapper.class);
    BusinessCalendarMapper businessCalendarMapper = Mockito.mock(BusinessCalendarMapper.class);
    WorkflowNodeMapper nodeMapper = Mockito.mock(WorkflowNodeMapper.class);
    WorkflowEdgeMapper edgeMapper = Mockito.mock(WorkflowEdgeMapper.class);
    DefaultSingleJobWorkflowProvisioner provisioner = new DefaultSingleJobWorkflowProvisioner(
        definitionMapper, businessCalendarMapper, nodeMapper, edgeMapper);
    JobDefinitionEntity job = new JobDefinitionEntity();
    job.setTenantId("tenant-a");
    job.setId(17L);
    job.setJobCode("IMPORT_ORDERS");
    job.setJobName("Import Orders");
    job.setWindowCode("NIGHT");
    job.setCalendarCode("calendar-a");
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "calendar_a"))
        .thenReturn(new ActiveCalendarRow(
            1L, "tenant-a", "calendar_a", null, null, null, null, null, null));
    doAnswer(invocation -> {
          ((WorkflowDefinitionEntity) invocation.getArgument(0)).setId(28L);
          return 1;
        })
        .when(definitionMapper)
        .insert(ArgumentMatchers.any());

    provisioner.provision(job);

    ArgumentCaptor<WorkflowNodeUpsertParam> nodes =
        ArgumentCaptor.forClass(WorkflowNodeUpsertParam.class);
    Mockito.verify(nodeMapper, Mockito.times(3)).upsertWorkflowNode(nodes.capture());
    assertThat(nodes.getAllValues())
        .filteredOn(node -> "JOB".equals(node.getNodeType()))
        .singleElement()
        .satisfies(node -> assertThat(node.getWindowCode()).isEqualTo("NIGHT"));
  }

  @Test
  @DisplayName("Job 未配置日历时受管 Workflow 使用租户默认业务日历")
  void shouldUseTenantDefaultCalendarWhenJobCalendarIsMissing() {
    WorkflowDefinitionMapper definitionMapper = Mockito.mock(WorkflowDefinitionMapper.class);
    BusinessCalendarMapper businessCalendarMapper = Mockito.mock(BusinessCalendarMapper.class);
    WorkflowNodeMapper nodeMapper = Mockito.mock(WorkflowNodeMapper.class);
    WorkflowEdgeMapper edgeMapper = Mockito.mock(WorkflowEdgeMapper.class);
    DefaultSingleJobWorkflowProvisioner provisioner = new DefaultSingleJobWorkflowProvisioner(
        definitionMapper, businessCalendarMapper, nodeMapper, edgeMapper);
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "default_calendar"))
        .thenReturn(new ActiveCalendarRow(
            1L, "tenant-a", "default_calendar", null, null, null, null, null, null));
    JobDefinitionEntity job = new JobDefinitionEntity();
    job.setTenantId("tenant-a");
    job.setId(17L);
    job.setJobCode("EXPORT_REPORT");
    job.setJobName("Export Report");
    doAnswer(invocation -> {
          ((WorkflowDefinitionEntity) invocation.getArgument(0)).setId(28L);
          return 1;
        })
        .when(definitionMapper)
        .insert(ArgumentMatchers.any());

    provisioner.provision(job);

    ArgumentCaptor<WorkflowDefinitionEntity> workflows =
        ArgumentCaptor.forClass(WorkflowDefinitionEntity.class);
    verify(definitionMapper).insert(workflows.capture());
    WorkflowDefinitionEntity persistedWorkflow = workflows.getValue();
    String persistedCalendarCode = persistedWorkflow.getCalendarCode();
    assertThat(persistedCalendarCode).isEqualTo("default_calendar");
  }

  @Test
  @DisplayName("租户没有默认业务日历时拒绝创建缺少日历身份的单作业 Workflow")
  void shouldRejectMissingTenantDefaultCalendar() {
    WorkflowDefinitionMapper definitionMapper = Mockito.mock(WorkflowDefinitionMapper.class);
    BusinessCalendarMapper businessCalendarMapper = Mockito.mock(BusinessCalendarMapper.class);
    WorkflowNodeMapper nodeMapper = Mockito.mock(WorkflowNodeMapper.class);
    WorkflowEdgeMapper edgeMapper = Mockito.mock(WorkflowEdgeMapper.class);
    DefaultSingleJobWorkflowProvisioner provisioner = new DefaultSingleJobWorkflowProvisioner(
        definitionMapper, businessCalendarMapper, nodeMapper, edgeMapper);
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "default_calendar"))
        .thenReturn(null);
    JobDefinitionEntity job = new JobDefinitionEntity();
    job.setTenantId("tenant-a");
    job.setId(17L);
    job.setJobCode("EXPORT_REPORT");

    assertThatThrownBy(() -> provisioner.provision(job)).isInstanceOf(BizException.class);

    Mockito.verify(definitionMapper, never()).insert(ArgumentMatchers.any());
    verifyNoInteractions(nodeMapper, edgeMapper);
  }

  @Test
  @DisplayName("Job 引用不存在的业务日历时拒绝创建受管 Workflow")
  void shouldRejectUnknownExplicitCalendar() {
    WorkflowDefinitionMapper definitionMapper = Mockito.mock(WorkflowDefinitionMapper.class);
    BusinessCalendarMapper businessCalendarMapper = Mockito.mock(BusinessCalendarMapper.class);
    WorkflowNodeMapper nodeMapper = Mockito.mock(WorkflowNodeMapper.class);
    WorkflowEdgeMapper edgeMapper = Mockito.mock(WorkflowEdgeMapper.class);
    DefaultSingleJobWorkflowProvisioner provisioner = new DefaultSingleJobWorkflowProvisioner(
        definitionMapper, businessCalendarMapper, nodeMapper, edgeMapper);
    JobDefinitionEntity job = new JobDefinitionEntity();
    job.setTenantId("tenant-a");
    job.setId(17L);
    job.setJobCode("EXPORT_REPORT");
    job.setCalendarCode("missing-calendar");
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "missing_calendar"))
        .thenReturn(null);

    assertThatThrownBy(() -> provisioner.provision(job)).isInstanceOf(BizException.class);

    verify(definitionMapper, never()).insert(ArgumentMatchers.any());
    verifyNoInteractions(nodeMapper, edgeMapper);
  }

  @Test
  @DisplayName("已存在显式 Job 关联时复用,避免配置包重复导入创建重复 Workflow")
  void shouldReuseExistingManagedWorkflow_whenProvisionIsRepeated() {
    WorkflowDefinitionMapper definitionMapper = Mockito.mock(WorkflowDefinitionMapper.class);
    BusinessCalendarMapper businessCalendarMapper = Mockito.mock(BusinessCalendarMapper.class);
    WorkflowNodeMapper nodeMapper = Mockito.mock(WorkflowNodeMapper.class);
    WorkflowEdgeMapper edgeMapper = Mockito.mock(WorkflowEdgeMapper.class);
    DefaultSingleJobWorkflowProvisioner provisioner = new DefaultSingleJobWorkflowProvisioner(
        definitionMapper, businessCalendarMapper, nodeMapper, edgeMapper);
    JobDefinitionEntity job = new JobDefinitionEntity();
    job.setTenantId("tenant-a");
    job.setId(17L);
    WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity();
    existing.setId(28L);
    existing.setSingleJobDefinitionId(job.getId());
    when(definitionMapper.selectBySingleJobDefinitionId(job.getTenantId(), job.getId()))
        .thenReturn(existing);

    provisioner.provision(job);

    verify(definitionMapper).selectBySingleJobDefinitionId(job.getTenantId(), job.getId());
    verify(definitionMapper, never()).insert(ArgumentMatchers.any());
    verifyNoMoreInteractions(definitionMapper);
    verifyNoInteractions(businessCalendarMapper, nodeMapper, edgeMapper);
  }
}
