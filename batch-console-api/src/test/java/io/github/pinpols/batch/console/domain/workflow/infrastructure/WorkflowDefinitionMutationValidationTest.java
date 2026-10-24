package io.github.pinpols.batch.console.domain.workflow.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.support.JobResourceAssignmentGuard;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService.LockHolder;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionFullUpdateRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionVersionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionUpdateParam;
import io.github.pinpols.batch.console.domain.workflow.validation.WorkflowDagValidator;
import io.github.pinpols.batch.console.infrastructure.workflow.DefaultWorkflowDefinitionService;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionDagInspector;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionResponseAssembler;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionWriteSupport;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 创建与全量替换入口必须强制走 DAG validator。 */
@DisplayName("workflow create/fullUpdate 强制 DAG 校验")
class WorkflowDefinitionMutationValidationTest {

  private static final String TENANT = "t1";
  private static final long DEF_ID = 100L;

  private WorkflowDefinitionMapper definitionMapper;
  private WorkflowNodeMapper nodeMapper;
  private WorkflowEdgeMapper edgeMapper;
  private BusinessCalendarMapper businessCalendarMapper;
  private WorkflowDagValidator dagValidator;
  private JobDefinitionMapper jobDefinitionMapper;
  private WorkflowDesignLockService designLockService;
  private DefaultWorkflowDefinitionService service;
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @BeforeEach
  void setUp() {
    definitionMapper = mock(WorkflowDefinitionMapper.class);
    nodeMapper = mock(WorkflowNodeMapper.class);
    edgeMapper = mock(WorkflowEdgeMapper.class);
    businessCalendarMapper = mock(BusinessCalendarMapper.class);
    dagValidator = mock(WorkflowDagValidator.class);
    jobDefinitionMapper = mock(JobDefinitionMapper.class);
    ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
    when(tenantGuard.resolveTenant(TENANT)).thenReturn(TENANT);

    designLockService = mock(WorkflowDesignLockService.class);
    service = new DefaultWorkflowDefinitionService(
        definitionMapper,
        nodeMapper,
        edgeMapper,
        mock(WorkflowDefinitionVersionMapper.class),
        mock(ConsoleRealtimeEventPort.class),
        tenantGuard,
        mock(ConsoleConfigCacheInvalidationService.class),
        designLockService,
        dagValidator,
        new WorkflowDefinitionResponseAssembler(),
        new WorkflowDefinitionWriteSupport(
            nodeMapper,
            edgeMapper,
            mock(WorkflowDefinitionVersionMapper.class),
            new ObjectMapper()),
        new WorkflowDefinitionDagInspector(jobDefinitionMapper),
        mock(JobResourceAssignmentGuard.class),
        jobDefinitionMapper,
        businessCalendarMapper);
  }

  private WorkflowDefinitionSaveRequest request() {
    WorkflowDefinitionSaveRequest req = new WorkflowDefinitionSaveRequest();
    req.setTenantId(TENANT);
    req.setWorkflowCode("WF_CYCLE");
    req.setWorkflowName("含环工作流");
    req.setWorkflowType("STANDARD");
    return req;
  }

  @Test
  @DisplayName("create:validator 抛错 → 异常传播,绝不 insert 节点/边")
  void create_blocksPersist_whenValidatorThrows() {
    // arrange
    when(definitionMapper.selectByUniqueKey(TENANT, "WF_CYCLE", 1)).thenReturn(null);
    doThrow(BizException.of(ResultCode.VALIDATION_ERROR, "error.workflow.dag.cycle_detected"))
        .when(dagValidator)
        .validate(eq(TENANT), any());

    // act + assert
    assertThatThrownBy(() -> service.create(request())).isInstanceOf(BizException.class);
    verify(dagValidator).validate(eq(TENANT), any());
    verify(definitionMapper, never()).insert(any());
    verify(nodeMapper, never()).upsertWorkflowNode(any());
  }

  @Test
  @DisplayName("create:拒绝当前租户不存在的日历,不写入定义")
  void create_blocksPersist_whenCalendarReferenceIsUnknown() {
    when(definitionMapper.selectByUniqueKey(TENANT, "WF_CYCLE", 1)).thenReturn(null);
    WorkflowDefinitionSaveRequest body = request();
    body.setCalendarCode("missing-calendar");
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode(TENANT, "missing_calendar"))
        .thenReturn(null);

    assertThatThrownBy(() -> service.create(body))
        .isInstanceOf(BizException.class)
        .extracting(exception -> ((BizException) exception).getMessageKey())
        .isEqualTo("error.workflow.calendar_not_found");

    verify(definitionMapper, never()).insert(any());
    verify(nodeMapper, never()).upsertWorkflowNode(any());
  }

  @Test
  @DisplayName("fullUpdate:拒绝当前租户不存在的日历,不更新定义")
  void fullUpdate_blocksPersist_whenCalendarReferenceIsUnknown() {
    WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
    def.setId(DEF_ID);
    def.setTenantId(TENANT);
    def.setWorkflowCode("WF_CYCLE");
    def.setWorkflowType("DAG");
    def.setVersion(1);
    when(definitionMapper.selectById(TENANT, DEF_ID)).thenReturn(def);
    when(designLockService.currentHolder(TENANT, DEF_ID))
        .thenReturn(new LockHolder("alice", Instant.MAX));
    WorkflowDefinitionSaveRequest body = request();
    body.setCalendarCode("missing-calendar");
    body.setScheduleType("MANUAL");
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode(TENANT, "missing_calendar"))
        .thenReturn(null);

    assertThatThrownBy(() -> service.fullUpdate(DEF_ID, fullUpdateRequest(body, 1), "alice"))
        .isInstanceOf(BizException.class)
        .extracting(exception -> ((BizException) exception).getMessageKey())
        .isEqualTo("error.workflow.calendar_not_found");

    verify(definitionMapper, never())
        .updateAndBumpVersion(any(WorkflowDefinitionVersionUpdateParam.class));
    verify(nodeMapper, never()).deleteByWorkflowDefinitionId(any());
  }

  @Test
  @DisplayName("fullUpdate:存在周期预期配置时拒绝改变Workflow日历")
  void fullUpdate_blocksCalendarChange_whenRecurringExpectationsExist() {
    WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
    def.setId(DEF_ID);
    def.setTenantId(TENANT);
    def.setWorkflowCode("WF_CYCLE");
    def.setWorkflowType("DAG");
    def.setVersion(1);
    def.setCalendarCode("old_calendar");
    when(definitionMapper.selectById(TENANT, DEF_ID)).thenReturn(def);
    when(designLockService.currentHolder(TENANT, DEF_ID))
        .thenReturn(new LockHolder("alice", Instant.MAX));
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode(TENANT, "new_calendar"))
        .thenReturn(Map.of());
    when(definitionMapper.hasCalendarScopedExpectations(TENANT, DEF_ID)).thenReturn(true);
    WorkflowDefinitionSaveRequest body = request();
    body.setCalendarCode("new_calendar");
    body.setScheduleType("MANUAL");

    assertThatThrownBy(() -> service.fullUpdate(DEF_ID, fullUpdateRequest(body, 1), "alice"))
        .isInstanceOf(BizException.class)
        .extracting(exception -> ((BizException) exception).getMessageKey())
        .isEqualTo("error.workflow.calendar_has_expectations");

    verify(definitionMapper, never())
        .updateAndBumpVersion(any(WorkflowDefinitionVersionUpdateParam.class));
    verify(nodeMapper, never()).deleteByWorkflowDefinitionId(any());
  }

  @Test
  @DisplayName("fullUpdate:validator 抛错 → 异常传播,绝不更新定义或删建节点")
  void fullUpdate_blocksPersist_whenValidatorThrows() {
    // arrange
    WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
    def.setId(DEF_ID);
    def.setTenantId(TENANT);
    def.setWorkflowCode("WF_CYCLE");
    def.setWorkflowType("DAG");
    def.setVersion(1);
    when(definitionMapper.selectById(TENANT, DEF_ID)).thenReturn(def);
    when(designLockService.currentHolder(TENANT, DEF_ID))
        .thenReturn(new LockHolder("alice", Instant.MAX));
    doThrow(BizException.of(ResultCode.VALIDATION_ERROR, "error.workflow.dag.cycle_detected"))
        .when(dagValidator)
        .validate(eq(TENANT), any());

    // act + assert
    WorkflowDefinitionSaveRequest body = request();
    body.setWorkflowCode("WF_CYCLE");
    assertThatThrownBy(() -> service.fullUpdate(DEF_ID, fullUpdateRequest(body, 1), "alice"))
        .isInstanceOf(BizException.class);
    verify(dagValidator).validate(eq(TENANT), any());
    verify(definitionMapper, never())
        .updateAndBumpVersion(any(WorkflowDefinitionVersionUpdateParam.class));
    verify(nodeMapper, never()).deleteByWorkflowDefinitionId(any());
  }

  @Test
  @DisplayName("受管单作业工作流拒绝删除或替换系统图")
  void fullUpdate_blocksManagedSingleJobGraphMutation() {
    WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
    def.setId(DEF_ID);
    def.setTenantId(TENANT);
    def.setWorkflowCode("JOB-WF-7");
    def.setWorkflowType("DAG");
    def.setVersion(1);
    def.setSingleJobDefinitionId(7L);
    when(definitionMapper.selectById(TENANT, DEF_ID)).thenReturn(def);
    when(designLockService.currentHolder(TENANT, DEF_ID))
        .thenReturn(new LockHolder("alice", Instant.MAX));
    WorkflowDefinitionSaveRequest body = request();
    body.setWorkflowCode("JOB-WF-7");
    assertThatThrownBy(() -> service.fullUpdate(DEF_ID, fullUpdateRequest(body, 1), "alice"))
        .isInstanceOf(BizException.class)
        .extracting(exception -> ((BizException) exception).getMessageKey())
        .isEqualTo("error.workflow.single_job_graph_managed");
    verify(dagValidator, never()).validate(eq(TENANT), any());
    verify(definitionMapper, never())
        .updateAndBumpVersion(any(WorkflowDefinitionVersionUpdateParam.class));
    verify(nodeMapper, never()).deleteByWorkflowDefinitionId(any());
  }

  private WorkflowDefinitionFullUpdateRequest fullUpdateRequest(
      WorkflowDefinitionSaveRequest definition, int expectedVersion) {
    WorkflowDefinitionFullUpdateRequest request = new WorkflowDefinitionFullUpdateRequest();
    request.setDefinition(definition);
    request.setExpectedVersion(expectedVersion);
    return request;
  }

  @Test
  @DisplayName("受管单作业 Workflow 允许修改固定 JOB 节点的执行窗口")
  void fullUpdate_allowsWindowOverrideOnManagedJobNode() {
    JobDefinitionEntity job = new JobDefinitionEntity();
    job.setJobCode("job_7");
    job.setEnabled(true);
    when(jobDefinitionMapper.selectByUniqueKey(TENANT, "job_7")).thenReturn(job);
    WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
    def.setId(DEF_ID);
    def.setTenantId(TENANT);
    def.setWorkflowCode("JOB-WF-7");
    def.setWorkflowName("Managed Job");
    def.setWorkflowType("DAG");
    def.setVersion(2);
    def.setEnabled(true);
    def.setSingleJobDefinitionId(7L);
    when(definitionMapper.selectById(TENANT, DEF_ID)).thenReturn(def);
    WorkflowNodeEntity existingJobNode = new WorkflowNodeEntity();
    existingJobNode.setNodeType("JOB");
    existingJobNode.setRelatedJobCode("job_7");
    when(nodeMapper.selectByQuery(any())).thenReturn(List.of(existingJobNode));
    when(designLockService.currentHolder(TENANT, DEF_ID))
        .thenReturn(new LockHolder("alice", Instant.MAX));

    WorkflowDefinitionSaveRequest body = request();
    body.setWorkflowCode("JOB-WF-7");
    body.setWorkflowName("Managed Job");
    body.setWorkflowType("DAG");
    body.setNodes(List.of(
        managedNode("START", "START", null, 0, null),
        managedNode("JOB", "JOB", "job_7", 1, "NIGHT"),
        managedNode("END", "END", null, 2, null)));
    body.setEdges(List.of(managedEdge("START", "JOB"), managedEdge("JOB", "END")));
    assertThat(body.getNodes()).hasSize(3);
    assertThat(body.getEdges()).hasSize(2);
    assertThat(body.getEdges())
        .allSatisfy(edge -> assertThat(edge.getConditionExpr()).isNull());
    assertThat(body.getEdges())
        .extracting(
            edge -> edge.getFromNodeCode() + ">" + edge.getToNodeCode() + ":" + edge.getEdgeType())
        .containsExactly("START>JOB:SUCCESS", "JOB>END:SUCCESS");
    WorkflowDefinitionFullUpdateRequest fullUpdate = new WorkflowDefinitionFullUpdateRequest();
    fullUpdate.setDefinition(body);
    fullUpdate.setExpectedVersion(2);

    assertThatThrownBy(() -> service.fullUpdate(DEF_ID, fullUpdate, "alice"))
        .isInstanceOf(BizException.class)
        .extracting(exception -> ((BizException) exception).getMessageKey())
        .isEqualTo("error.workflow_full_update.version_conflict");
  }

  private WorkflowDefinitionSaveRequest.NodeItem managedNode(
      String code, String type, String jobCode, int order, String windowCode) {
    WorkflowDefinitionSaveRequest.NodeItem node = new WorkflowDefinitionSaveRequest.NodeItem();
    node.setNodeCode(code);
    node.setNodeName(code);
    node.setNodeType(type);
    node.setRelatedJobCode(jobCode);
    node.setNodeOrder(order);
    node.setWindowCode(windowCode);
    node.setRetryPolicy("NONE");
    node.setRetryMaxCount(0);
    node.setTimeoutSeconds(0);
    node.setEnabled(true);
    return node;
  }

  private WorkflowDefinitionSaveRequest.EdgeItem managedEdge(String from, String to) {
    WorkflowDefinitionSaveRequest.EdgeItem edge = new WorkflowDefinitionSaveRequest.EdgeItem();
    edge.setFromNodeCode(from);
    edge.setToNodeCode(to);
    edge.setEdgeType("SUCCESS");
    edge.setEnabled(true);
    return edge;
  }

  @Test
  @DisplayName("Workflow 调度校验拒绝无效 Cron、非正固定频率和非法时区")
  void scheduleConfiguration_rejectsInvalidScheduleValues() {
    WorkflowDefinitionSaveRequest request = request();
    request.setScheduleType("CRON");
    request.setScheduleExpr("not-a-cron");
    request.setTimezone("Mars/Phobos");

    assertThat(validator.validate(request))
        .anyMatch(violation ->
            violation.getPropertyPath().toString().equals("scheduleConfigurationValid"));

    request.setScheduleType("FIXED_RATE");
    request.setScheduleExpr("0");
    request.setTimezone("UTC");
    assertThat(validator.validate(request))
        .anyMatch(violation ->
            violation.getPropertyPath().toString().equals("scheduleConfigurationValid"));
  }
}
