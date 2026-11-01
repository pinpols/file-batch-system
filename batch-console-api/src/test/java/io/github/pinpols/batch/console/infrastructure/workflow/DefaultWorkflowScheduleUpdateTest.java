package io.github.pinpols.batch.console.infrastructure.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.support.JobResourceAssignmentGuard;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.ActiveCalendarRow;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionView;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowScheduleUpdateRequest;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionVersionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionInsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowScheduleUpdateParam;
import io.github.pinpols.batch.console.domain.workflow.validation.WorkflowDagValidator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow 调度更新的依赖校验与版本快照")
class DefaultWorkflowScheduleUpdateTest {

  @Mock
  private WorkflowDefinitionMapper definitionMapper;

  @Mock
  private WorkflowNodeMapper nodeMapper;

  @Mock
  private WorkflowEdgeMapper edgeMapper;

  @Mock
  private WorkflowDefinitionVersionMapper versionMapper;

  @Mock
  private ConsoleRealtimeEventPort eventPort;

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @Mock
  private ConsoleConfigCacheInvalidationService cacheInvalidationService;

  @Mock
  private WorkflowDesignLockService designLockService;

  @Mock
  private WorkflowDagValidator dagValidator;

  @Mock
  private JobDefinitionMapper jobDefinitionMapper;

  @Mock
  private BusinessCalendarMapper businessCalendarMapper;

  @Mock
  private JobResourceAssignmentGuard resourceAssignmentGuard;

  private DefaultWorkflowDefinitionService service;

  @BeforeEach
  void setUp() {
    service = new DefaultWorkflowDefinitionService(
        definitionMapper,
        nodeMapper,
        edgeMapper,
        versionMapper,
        eventPort,
        tenantGuard,
        cacheInvalidationService,
        designLockService,
        dagValidator,
        new WorkflowDefinitionResponseAssembler(),
        new WorkflowDefinitionWriteSupport(
            nodeMapper, edgeMapper, versionMapper, new ObjectMapper()),
        new WorkflowDefinitionDagInspector(jobDefinitionMapper),
        resourceAssignmentGuard,
        jobDefinitionMapper,
        businessCalendarMapper);
  }

  @DisplayName("更新调度并固定新定义版本快照")
  @Test
  void shouldUpdatesOnlyScheduleAndSnapshotsTheNewDefinitionVersion() {
    WorkflowDefinitionEntity current = definition(4, "MANUAL", null);
    WorkflowDefinitionEntity updated = definition(5, "CRON", "0 0 2 * * *");
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    WorkflowScheduleUpdateRequest request = scheduleRequest();
    request.setDependsOnJobCode("upstream_job");
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "upstream_job"))
        .thenReturn(jobDefinition(91L, "upstream_job"));
    when(definitionMapper.selectById("tenant-a", 17L)).thenReturn(current, updated);
    when(definitionMapper.updateScheduleConfiguration(new WorkflowScheduleUpdateParam(
            "tenant-a",
            17L,
            4,
            "CRON",
            "0 0 2 * * *",
            "Asia/Shanghai",
            "SCHEDULED",
            null,
            "upstream_job")))
        .thenReturn(1);
    when(nodeMapper.selectByQuery(any())).thenReturn(List.of());
    when(edgeMapper.selectByQuery(any())).thenReturn(List.of());

    service.updateSchedule(17L, request, "operator");

    verify(definitionMapper)
        .updateScheduleConfiguration(new WorkflowScheduleUpdateParam(
            "tenant-a",
            17L,
            4,
            "CRON",
            "0 0 2 * * *",
            "Asia/Shanghai",
            "SCHEDULED",
            null,
            "upstream_job"));
    ArgumentCaptor<WorkflowDefinitionVersionInsertParam> snapshotCaptor =
        ArgumentCaptor.forClass(WorkflowDefinitionVersionInsertParam.class);
    verify(versionMapper).insertVersionSnapshot(snapshotCaptor.capture());
    assertThat(snapshotCaptor.getValue().getVersion()).isEqualTo(5);
    assertThat(snapshotCaptor.getValue().getScheduleType()).isEqualTo("CRON");
    verify(nodeMapper, never()).deleteByWorkflowDefinitionId(any());
    verify(edgeMapper, never()).deleteByWorkflowDefinitionId(any());
  }

  @DisplayName("更新调度前拒绝未知依赖")
  @Test
  void shouldUnknownScheduleDependencyIsRejectedBeforeUpdatingSchedule() {
    WorkflowDefinitionEntity current = definition(4, "MANUAL", null);
    WorkflowScheduleUpdateRequest request = scheduleRequest();
    request.setDependsOnJobCode("missing_job");
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(definitionMapper.selectById("tenant-a", 17L)).thenReturn(current);
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "missing_job")).thenReturn(null);

    assertThatThrownBy(() -> service.updateSchedule(17L, request, "operator"))
        .isInstanceOf(BizException.class);

    verify(definitionMapper, never())
        .updateScheduleConfiguration(any(WorkflowScheduleUpdateParam.class));
    verify(versionMapper, never()).insertVersionSnapshot(any());
  }

  @DisplayName("更新调度前拒绝租户中不存在的业务日历")
  @Test
  void shouldRejectUnknownCalendarBeforeUpdatingSchedule() {
    WorkflowDefinitionEntity current = definition(4, "MANUAL", null);
    WorkflowScheduleUpdateRequest request = scheduleRequest();
    request.setCalendarCode("missing-calendar");
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(definitionMapper.selectById("tenant-a", 17L)).thenReturn(current);
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "missing_calendar"))
        .thenReturn(null);
    assertThatThrownBy(() -> service.updateSchedule(17L, request, "operator"))
        .isInstanceOf(BizException.class);

    verify(definitionMapper, never())
        .updateScheduleConfiguration(any(WorkflowScheduleUpdateParam.class));
    verify(versionMapper, never()).insertVersionSnapshot(any());
  }

  @DisplayName("存在日历预期定义时拒绝修改Workflow日历")
  @Test
  void shouldRejectCalendarChangeWhileRecurringExpectationsExist() {
    WorkflowDefinitionEntity current = definition(4, "MANUAL", null);
    current.setCalendarCode("old_calendar");
    WorkflowScheduleUpdateRequest request = scheduleRequest();
    request.setCalendarCode("new_calendar");
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(definitionMapper.selectById("tenant-a", 17L)).thenReturn(current);
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "new_calendar"))
        .thenReturn(new ActiveCalendarRow(
            1L, "tenant-a", "new_calendar", null, null, null, null, null, null));
    when(definitionMapper.hasCalendarScopedExpectations("tenant-a", 17L)).thenReturn(true);

    BizException failure = catchThrowableOfType(
        BizException.class, () -> service.updateSchedule(17L, request, "operator"));
    assertThat(failure).isNotNull();

    assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT);
    verify(definitionMapper, never())
        .updateScheduleConfiguration(any(WorkflowScheduleUpdateParam.class));
    verify(versionMapper, never()).insertVersionSnapshot(any());
  }

  @DisplayName("调度依赖不得指向受管作业自身")
  @Test
  void shouldScheduleCannotDependOnManagedJobItself() {
    WorkflowDefinitionEntity current = definition(4, "MANUAL", null);
    current.setSingleJobDefinitionId(91L);
    WorkflowScheduleUpdateRequest request = scheduleRequest();
    request.setDependsOnJobCode("managed_job");
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(definitionMapper.selectById("tenant-a", 17L)).thenReturn(current);
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "managed_job"))
        .thenReturn(jobDefinition(91L, "managed_job"));

    assertThatThrownBy(() -> service.updateSchedule(17L, request, "operator"))
        .isInstanceOf(BizException.class);

    verify(definitionMapper, never())
        .updateScheduleConfiguration(any(WorkflowScheduleUpdateParam.class));
  }

  @DisplayName("过时定义版本不得写入新快照")
  @Test
  void shouldStaleDefinitionVersionDoesNotWriteSnapshot() {
    WorkflowDefinitionEntity current = definition(5, "MANUAL", null);
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(definitionMapper.selectById("tenant-a", 17L)).thenReturn(current);
    when(definitionMapper.updateScheduleConfiguration(new WorkflowScheduleUpdateParam(
            "tenant-a", 17L, 4, "CRON", "0 0 2 * * *", "Asia/Shanghai", "SCHEDULED", null, null)))
        .thenReturn(0);

    assertThatThrownBy(() -> service.updateSchedule(17L, scheduleRequest(), "operator"))
        .isInstanceOf(BizException.class);

    verify(versionMapper, never()).insertVersionSnapshot(any());
  }

  private static WorkflowDefinitionEntity definition(
      int version, String scheduleType, String expr) {
    WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
    entity.setId(17L);
    entity.setTenantId("tenant-a");
    entity.setWorkflowCode("daily_flow");
    entity.setWorkflowName("Daily flow");
    entity.setWorkflowType("DAG");
    entity.setVersion(version);
    entity.setEnabled(true);
    entity.setScheduleType(scheduleType);
    entity.setScheduleExpr(expr);
    return entity;
  }

  private static JobDefinitionView jobDefinition(Long id, String jobCode) {
    JobDefinitionView entity = new JobDefinitionView();
    entity.setId(id);
    entity.setJobCode(jobCode);
    return entity;
  }

  private static WorkflowScheduleUpdateRequest scheduleRequest() {
    WorkflowScheduleUpdateRequest request = new WorkflowScheduleUpdateRequest();
    request.setTenantId("tenant-a");
    request.setExpectedVersion(4);
    request.setScheduleType("CRON");
    request.setScheduleExpr("0 0 2 * * *");
    request.setTimezone("Asia/Shanghai");
    request.setTriggerMode("SCHEDULED");
    return request;
  }
}
