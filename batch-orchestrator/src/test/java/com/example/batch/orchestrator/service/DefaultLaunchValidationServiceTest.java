package com.example.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import java.time.LocalDate;
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
 *   <li>WORKFLOW 类型缺 workflow_definition → trigger_request REJECTED + 抛错
 *   <li>非 WORKFLOW 类型不强制 workflow_definition,合法 launch 应能拿到 LaunchLoadResult
 * </ul>
 */
class DefaultLaunchValidationServiceTest {

  @Mock private TriggerRequestMapper triggerRequestMapper;
  @Mock private OrchestratorConfigCacheService configCacheService;
  @Mock private JobInstanceMapper jobInstanceMapper;

  private DefaultLaunchValidationService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service =
        new DefaultLaunchValidationService(
            triggerRequestMapper, configCacheService, jobInstanceMapper);
  }

  private LaunchRequest validRequest() {
    return new LaunchRequest(
        "ta",
        "job_ok",
        LocalDate.of(2026, 5, 20),
        TriggerType.SCHEDULED,
        "req-001",
        "trace-001",
        Map.of());
  }

  @Test
  @DisplayName("缺 tenantId → INVALID_ARGUMENT")
  void rejects_missing_tenantId() {
    LaunchRequest bad =
        new LaunchRequest(
            null, "j", LocalDate.now(), TriggerType.SCHEDULED, "req", "trace", Map.of());
    assertThatThrownBy(() -> service.load(bad)).isInstanceOf(BizException.class);
    verify(triggerRequestMapper, never()).selectByTenantAndRequestId(anyString(), anyString());
  }

  @Test
  @DisplayName("缺 jobCode → INVALID_ARGUMENT")
  void rejects_missing_jobCode() {
    LaunchRequest bad =
        new LaunchRequest(
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
  void rejects_when_trigger_request_missing() {
    LaunchRequest req = validRequest();
    when(triggerRequestMapper.selectByTenantAndRequestId(eq("ta"), eq("req-001"))).thenReturn(null);

    assertThatThrownBy(() -> service.load(req)).isInstanceOf(BizException.class);
    verify(triggerRequestMapper, never())
        .updateAcceptance(anyString(), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("job_definition 缺失 → trigger_request 打 REJECTED + 抛 NOT_FOUND")
  void rejects_when_jobDefinition_missing_and_marks_rejected() {
    LaunchRequest req = validRequest();
    when(triggerRequestMapper.selectByTenantAndRequestId(eq("ta"), eq("req-001")))
        .thenReturn(triggerRequestEntity());
    when(configCacheService.findEnabledJobDefinition(eq("ta"), eq("job_ok"))).thenReturn(null);

    assertThatThrownBy(() -> service.load(req)).isInstanceOf(BizException.class);
    verify(triggerRequestMapper)
        .updateAcceptance(eq("ta"), eq("req-001"), eq(BatchStatusConstants.REJECTED), any());
  }

  @Test
  @DisplayName("WORKFLOW 类型缺 workflow_definition → trigger_request 打 REJECTED + 抛 NOT_FOUND")
  void rejects_when_workflow_type_missing_workflow_def() {
    LaunchRequest req = validRequest();
    when(triggerRequestMapper.selectByTenantAndRequestId(eq("ta"), eq("req-001")))
        .thenReturn(triggerRequestEntity());
    when(configCacheService.findEnabledJobDefinition(eq("ta"), eq("job_ok")))
        .thenReturn(jobDefinitionEntity(JobType.WORKFLOW.code()));
    when(configCacheService.findEnabledWorkflowDefinition(eq("ta"), eq("job_ok"))).thenReturn(null);

    assertThatThrownBy(() -> service.load(req)).isInstanceOf(BizException.class);
    verify(triggerRequestMapper)
        .updateAcceptance(eq("ta"), eq("req-001"), eq(BatchStatusConstants.REJECTED), any());
  }

  @Test
  @DisplayName("非 WORKFLOW 类型不要求 workflow_definition,合法 launch 返回 LaunchLoadResult")
  void accepts_non_workflow_without_workflow_def() {
    LaunchRequest req = validRequest();
    TriggerRequestEntity trig = triggerRequestEntity();
    JobDefinitionEntity jobDef = jobDefinitionEntity(JobType.GENERAL.code());

    when(triggerRequestMapper.selectByTenantAndRequestId(eq("ta"), eq("req-001"))).thenReturn(trig);
    when(configCacheService.findEnabledJobDefinition(eq("ta"), eq("job_ok"))).thenReturn(jobDef);
    when(jobInstanceMapper.selectByTenantAndDedupKey(eq("ta"), anyString())).thenReturn(null);

    LaunchValidationService.LaunchLoadResult result = service.load(req);
    assertThat(result.triggerRequest()).isSameAs(trig);
    assertThat(result.jobDefinition()).isSameAs(jobDef);
    assertThat(result.workflowDefinition()).isNull();
    assertThat(result.existingInstance()).isNull();
    verify(configCacheService, never()).findEnabledWorkflowDefinition(anyString(), anyString());
  }

  @Test
  @DisplayName("WORKFLOW 类型 + workflow_definition 存在 → 正常返回")
  void accepts_workflow_with_workflow_def() {
    LaunchRequest req = validRequest();
    TriggerRequestEntity trig = triggerRequestEntity();
    JobDefinitionEntity jobDef = jobDefinitionEntity(JobType.WORKFLOW.code());
    WorkflowDefinitionEntity wf =
        new WorkflowDefinitionEntity(1L, "ta", "job_ok", "wf-name", "DAG", 1, true);
    JobInstanceEntity existing = new JobInstanceEntity();

    when(triggerRequestMapper.selectByTenantAndRequestId(eq("ta"), eq("req-001"))).thenReturn(trig);
    when(configCacheService.findEnabledJobDefinition(eq("ta"), eq("job_ok"))).thenReturn(jobDef);
    when(configCacheService.findEnabledWorkflowDefinition(eq("ta"), eq("job_ok"))).thenReturn(wf);
    when(jobInstanceMapper.selectByTenantAndDedupKey(eq("ta"), anyString())).thenReturn(existing);

    LaunchValidationService.LaunchLoadResult result = service.load(req);
    assertThat(result.workflowDefinition()).isSameAs(wf);
    assertThat(result.existingInstance()).isSameAs(existing);
  }

  private TriggerRequestEntity triggerRequestEntity() {
    TriggerRequestEntity entity = new TriggerRequestEntity();
    entity.setTenantId("ta");
    entity.setRequestId("req-001");
    entity.setDedupKey("dedup-1");
    return entity;
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
