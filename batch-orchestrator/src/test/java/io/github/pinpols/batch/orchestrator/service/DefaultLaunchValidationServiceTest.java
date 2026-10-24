package io.github.pinpols.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.context.TriggerLaunchPersistenceContext;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.LocalDate;
import java.time.Month;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Launch 入口校验与配置加载守护:
 *
 * <ul>
 *   <li>必填字段缺失 → INVALID_ARGUMENT,不读 DB / 缓存
 *   <li>trigger_request 不存在 → NOT_FOUND
 *   <li>job_definition 不存在 → trigger_request 打 REJECTED 后抛 NOT_FOUND
 *   <li>新 Job 通过 tenant + Job 定义 ID 加载受管 Workflow；缺失时拒绝，不能回退到同编码猜测
 *   <li>历史 WORKFLOW Job 在兼容期仍通过旧同编码入口加载 Workflow
 * </ul>
 */
@DisplayName("启动入口校验: 必填字段与定义缺失时的拒绝行为,以及合法启动的加载结果")
class DefaultLaunchValidationServiceTest {

  @Mock
  private TriggerRequestMapper triggerRequestMapper;

  @Mock
  private OrchestratorConfigCacheService configCacheService;

  @Mock
  private WorkflowRunMapper workflowRunMapper;

  private DefaultLaunchValidationService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new DefaultLaunchValidationService(
        triggerRequestMapper, configCacheService, workflowRunMapper);
  }

  private LaunchRequest validRequest() {
    return new LaunchRequest(
        "ta",
        "job_ok",
        LocalDate.of(2026, Month.MAY, 20),
        TriggerType.SCHEDULED,
        "req-001",
        "trace-001",
        Map.of());
  }

  @Test
  @DisplayName("缺 tenantId → INVALID_ARGUMENT")
  void rejects_missing_tenantId() {
    LaunchRequest bad = new LaunchRequest(
        null, "j", LocalDate.now(), TriggerType.SCHEDULED, "req", "trace", Map.of());
    assertThatThrownBy(() -> service.load(bad)).isInstanceOf(BizException.class);
    verify(triggerRequestMapper, never()).selectLaunchPersistenceContext(anyString(), anyString());
  }

  @Test
  @DisplayName("缺 jobCode → INVALID_ARGUMENT")
  void rejects_missing_jobCode() {
    LaunchRequest bad = new LaunchRequest(
        "ta", "  ", LocalDate.now(), TriggerType.SCHEDULED, "req", "trace", Map.of());
    assertThatThrownBy(() -> service.load(bad)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("缺 bizDate → INVALID_ARGUMENT")
  void rejects_missing_bizDate() {
    LaunchRequest bad =
        new LaunchRequest("ta", "j", null, TriggerType.SCHEDULED, "req", "trace", Map.of());
    assertThatThrownBy(() -> service.load(bad)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("缺 triggerType → INVALID_ARGUMENT")
  void rejects_missing_triggerType() {
    LaunchRequest bad =
        new LaunchRequest("ta", "j", LocalDate.now(), null, "req", "trace", Map.of());
    assertThatThrownBy(() -> service.load(bad)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("trigger_request 不存在 → NOT_FOUND,且不打 REJECTED(尚未确认 request 存在)")
  void shouldReject_whenTriggerRequestMissing() {
    LaunchRequest req = validRequest();
    when(triggerRequestMapper.selectLaunchPersistenceContext("ta", "req-001")).thenReturn(null);

    assertThatThrownBy(() -> service.load(req))
        .isInstanceOf(BizException.class)
        .extracting("messageKey")
        .isEqualTo("error.trigger.request_not_found");
    verify(triggerRequestMapper, never())
        .updateAcceptance(anyString(), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("job_definition 缺失 → trigger_request 打 REJECTED + 抛 NOT_FOUND")
  void rejects_when_jobDefinition_missing_and_marks_rejected() {
    LaunchRequest req = validRequest();
    when(triggerRequestMapper.selectLaunchPersistenceContext("ta", "req-001"))
        .thenReturn(persistenceContext(triggerRequestEntity(), null));
    when(configCacheService.findEnabledJobDefinition("ta", "job_ok")).thenReturn(null);

    assertThatThrownBy(() -> service.load(req)).isInstanceOf(BizException.class);
    verify(triggerRequestMapper)
        .updateAcceptance(eq("ta"), eq("req-001"), eq(TriggerRequestStatus.REJECTED.code()), any());
  }

  @Test
  @DisplayName("新 Job 缺少显式关联 Workflow → trigger_request 打 REJECTED + 抛 NOT_FOUND")
  void shouldReject_whenLinkedWorkflowDefinitionMissing() {
    LaunchRequest req = validRequest();
    when(triggerRequestMapper.selectLaunchPersistenceContext("ta", "req-001"))
        .thenReturn(persistenceContext(triggerRequestEntity(), null));
    when(configCacheService.findEnabledJobDefinition("ta", "job_ok"))
        .thenReturn(jobDefinitionEntity(JobType.GENERAL.code()));
    when(configCacheService.findEnabledSingleJobWorkflowDefinition("ta", 1L)).thenReturn(null);

    assertThatThrownBy(() -> service.load(req)).isInstanceOf(BizException.class);
    verify(triggerRequestMapper)
        .updateAcceptance(eq("ta"), eq("req-001"), eq(TriggerRequestStatus.REJECTED.code()), any());
  }

  @Test
  @DisplayName("普通 Job 按显式关联加载受管 Workflow，不按相同编码隐式查找")
  void shouldLoadLinkedWorkflow_whenJobTypeIsNotWorkflow() {
    LaunchRequest req = validRequest();
    TriggerRequestEntity trig = triggerRequestEntity();
    JobDefinitionEntity jobDef = jobDefinitionEntity(JobType.GENERAL.code());
    WorkflowDefinitionEntity wf =
        new WorkflowDefinitionEntity(9L, "ta", "JOB-WF-1", "wf-name", "DAG", 1, true, 1L);

    when(triggerRequestMapper.selectLaunchPersistenceContext("ta", "req-001"))
        .thenReturn(persistenceContext(trig, null));
    when(configCacheService.findEnabledJobDefinition("ta", "job_ok")).thenReturn(jobDef);
    when(configCacheService.findEnabledSingleJobWorkflowDefinition("ta", 1L)).thenReturn(wf);

    LaunchValidationService.LaunchLoadResult result = service.load(req);
    assertThat(result.triggerRequest()).isSameAs(trig);
    assertThat(result.jobDefinition()).isSameAs(jobDef);
    assertThat(result.workflowDefinition()).isSameAs(wf);
    assertThat(result.existingInstance()).isNull();
    verify(configCacheService, never()).findEnabledWorkflowDefinition(anyString(), anyString());
  }

  @Test
  @DisplayName("WORKFLOW 类型 + workflow_definition 存在 → 正常返回")
  void shouldLoadWorkflowDefinition_whenJobTypeIsWorkflow() {
    LaunchRequest req = validRequest();
    TriggerRequestEntity trig = triggerRequestEntity();
    JobDefinitionEntity jobDef = jobDefinitionEntity(JobType.WORKFLOW.code());
    WorkflowDefinitionEntity wf =
        new WorkflowDefinitionEntity(1L, "ta", "job_ok", "wf-name", "DAG", 1, true);
    JobInstanceEntity existing = new JobInstanceEntity();

    when(triggerRequestMapper.selectLaunchPersistenceContext("ta", "req-001"))
        .thenReturn(persistenceContext(trig, existing));
    when(configCacheService.findEnabledJobDefinition("ta", "job_ok")).thenReturn(jobDef);
    when(configCacheService.findEnabledWorkflowDefinition("ta", "job_ok")).thenReturn(wf);

    LaunchValidationService.LaunchLoadResult result = service.load(req);
    assertThat(result.workflowDefinition()).isSameAs(wf);
    assertThat(result.existingInstance()).isSameAs(existing);
  }

  @Test
  @DisplayName("普通 Job 作为父 Workflow 的 JOB 节点执行时不创建嵌套单作业 Workflow")
  void shouldNotLoadManagedWorkflowForVerifiedParentJobNodeLaunch() {
    LaunchRequest req = validRequest();
    TriggerRequestEntity trigger = triggerRequestEntity();
    trigger.setParentWorkflowRunId(50L);
    trigger.setParentJobInstanceId(60L);
    trigger.setParentNodeCode("IMPORT_NODE");
    trigger.setParentVirtualTaskId(70L);
    JobDefinitionEntity jobDef = jobDefinitionEntity(JobType.GENERAL.code());

    when(triggerRequestMapper.selectLaunchPersistenceContext("ta", "req-001"))
        .thenReturn(persistenceContext(trigger, null));
    when(configCacheService.findEnabledJobDefinition("ta", "job_ok")).thenReturn(jobDef);
    when(triggerRequestMapper.isValidParentWorkflowNodeLaunch(
            "ta", 50L, 60L, "IMPORT_NODE", 70L, "job_ok", null, false))
        .thenReturn(true);

    LaunchValidationService.LaunchLoadResult result = service.load(req);

    assertThat(result.workflowDefinition()).isNull();
    verify(configCacheService, never()).findEnabledSingleJobWorkflowDefinition(anyString(), any());
    verify(configCacheService, never()).findEnabledWorkflowDefinition(anyString(), anyString());
  }

  @Test
  @DisplayName("无根 Workflow 节点 Job 使用 Workflow+节点上下文校验，不要求父 Job/虚拟任务")
  void shouldAcceptRootlessWorkflowNodeJobLaunch() {
    LaunchRequest req = validRequest();
    TriggerRequestEntity trigger = triggerRequestEntity();
    trigger.setParentWorkflowRunId(50L);
    trigger.setParentNodeCode("IMPORT_NODE");
    JobDefinitionEntity jobDef = jobDefinitionEntity(JobType.GENERAL.code());

    when(triggerRequestMapper.selectLaunchPersistenceContext("ta", "req-001"))
        .thenReturn(persistenceContext(trigger, null));
    when(configCacheService.findEnabledJobDefinition("ta", "job_ok")).thenReturn(jobDef);
    when(triggerRequestMapper.isValidParentWorkflowNodeLaunch(
            "ta", 50L, null, "IMPORT_NODE", null, "job_ok", null, false))
        .thenReturn(true);

    LaunchValidationService.LaunchLoadResult result = service.load(req);

    assertThat(result.workflowDefinition()).isNull();
    verify(configCacheService, never()).findEnabledSingleJobWorkflowDefinition(anyString(), any());
  }

  @Test
  @DisplayName("新快照运行的 Job 节点从运行快照加载定义，不受后续 Job 编辑影响")
  void shouldLoadJobDefinitionFromImmutableWorkflowRunSnapshot() {
    LaunchRequest req = validRequest();
    TriggerRequestEntity trigger = triggerRequestEntity();
    trigger.setParentWorkflowRunId(50L);
    trigger.setParentNodeCode("IMPORT_NODE");
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setTenantId("ta");
    run.setId(50L);
    run.setWorkflowDefinitionId(90L);
    run.setWorkflowVersionSnapshot(7);
    run.setExecutionSnapshot(JsonUtils.toJson(Map.of(
        "schemaVersion",
        2,
        "workflowDefinitionId",
        90,
        "workflowVersion",
        7,
        "nodes",
        java.util.List.of(Map.of(
            "nodeCode", "IMPORT_NODE",
            "nodeType", "JOB",
            "tenantId", "ta",
            "workflowDefinitionId", 90,
            "relatedJobCode", "job_ok")),
        "edges",
        java.util.List.of(),
        "jobDefinitions",
        Map.of(
            "IMPORT_NODE",
            Map.of(
                "jobDefinitionId",
                1,
                "jobCode",
                "job_ok",
                "jobType",
                "GENERAL",
                "jobDefinitionVersion",
                1,
                "executionDefinition",
                Map.of(
                    "id",
                    1,
                    "tenantId",
                    "ta",
                    "jobCode",
                    "job_ok",
                    "jobType",
                    "GENERAL",
                    "version",
                    1,
                    "defaultParams",
                    Map.of("pinned", true)))))));
    when(triggerRequestMapper.selectLaunchPersistenceContext("ta", "req-001"))
        .thenReturn(persistenceContext(trigger, null));
    when(workflowRunMapper.selectById("ta", 50L)).thenReturn(run);
    when(triggerRequestMapper.isValidParentWorkflowNodeLaunch(
            "ta", 50L, null, "IMPORT_NODE", null, "job_ok", null, true))
        .thenReturn(true);

    LaunchValidationService.LaunchLoadResult result = service.load(req);

    assertThat(result.jobDefinition().defaultParams()).containsEntry("pinned", true);
    assertThat(result.jobDefinition().version()).isEqualTo(1);
    assertThat(result.workflowDefinition()).isNull();
    verify(configCacheService, never()).findEnabledJobDefinition("ta", "job_ok");
    verify(triggerRequestMapper)
        .isValidParentWorkflowNodeLaunch(
            "ta", 50L, null, "IMPORT_NODE", null, "job_ok", null, true);
  }

  @Test
  @DisplayName("伪造或过期的父 Workflow 节点关系拒绝启动，不回退为顶层运行")
  void shouldRejectInvalidParentJobNodeLaunch() {
    LaunchRequest req = validRequest();
    TriggerRequestEntity trigger = triggerRequestEntity();
    trigger.setParentWorkflowRunId(50L);
    trigger.setParentJobInstanceId(60L);
    trigger.setParentNodeCode("IMPORT_NODE");
    trigger.setParentVirtualTaskId(70L);

    when(triggerRequestMapper.selectLaunchPersistenceContext("ta", "req-001"))
        .thenReturn(persistenceContext(trigger, null));
    when(configCacheService.findEnabledJobDefinition("ta", "job_ok"))
        .thenReturn(jobDefinitionEntity(JobType.GENERAL.code()));
    when(triggerRequestMapper.isValidParentWorkflowNodeLaunch(
            anyString(),
            any(),
            any(),
            anyString(),
            any(),
            anyString(),
            any(),
            org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(false);

    assertThatThrownBy(() -> service.load(req))
        .isInstanceOf(BizException.class)
        .extracting("messageKey")
        .isEqualTo("error.workflow.parent_node_launch_invalid");
    verify(configCacheService, never()).findEnabledSingleJobWorkflowDefinition(anyString(), any());
    verify(triggerRequestMapper)
        .updateAcceptance(eq("ta"), eq("req-001"), eq(TriggerRequestStatus.REJECTED.code()), any());
  }

  private TriggerRequestEntity triggerRequestEntity() {
    TriggerRequestEntity entity = new TriggerRequestEntity();
    entity.setTenantId("ta");
    entity.setRequestId("req-001");
    entity.setDedupKey("dedup-1");
    return entity;
  }

  private TriggerLaunchPersistenceContext persistenceContext(
      TriggerRequestEntity triggerRequest, JobInstanceEntity existingInstance) {
    TriggerLaunchPersistenceContext context = new TriggerLaunchPersistenceContext();
    context.setTriggerRequest(triggerRequest);
    context.setExistingInstance(existingInstance);
    return context;
  }

  private JobDefinitionEntity jobDefinitionEntity(String jobType) {
    return JobDefinitionEntity.builder()
        .id(1L)
        .tenantId("ta")
        .jobCode("job_ok")
        .jobType(jobType)
        .build();
  }
}
