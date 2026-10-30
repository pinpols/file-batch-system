package io.github.pinpols.batch.console.domain.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowMonitoringPolicyEntity;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowMonitoringPolicyRequest;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowMonitoringPolicyMapper;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow 根级监控策略的身份与关闭语义")
class WorkflowMonitoringPolicyServiceTest {
  private static final String TENANT = "tenant-a";
  private static final long DEFINITION_ID = 41L;

  @Mock
  private TenantIdResolver tenantGuard;

  @Mock
  private WorkflowDefinitionMapper definitionMapper;

  @Mock
  private WorkflowMonitoringPolicyMapper policyMapper;

  private WorkflowMonitoringPolicyService service;

  @BeforeEach
  void setUp() {
    service = new WorkflowMonitoringPolicyService(tenantGuard, definitionMapper, policyMapper);
  }

  @Test
  @DisplayName("未保存策略时三类均关闭，不继承子 Job 阈值")
  void shouldReturnDisabledPolicyWhenNotConfigured() {
    existingDefinition();

    WorkflowMonitoringPolicyEntity policy = service.get(DEFINITION_ID, TENANT);

    assertThat(policy.getTenantId()).isEqualTo(TENANT);
    assertThat(policy.getWorkflowDefinitionId()).isEqualTo(DEFINITION_ID);
    assertThat(policy.getSoftRuntimeSeconds()).isZero();
    assertThat(policy.getStartGraceSeconds()).isZero();
    assertThat(policy.getCompletionDeadlineLocalTime()).isNull();
    verify(policyMapper, never()).upsert(any());
  }

  @Test
  @DisplayName("根定义不存在时不写入孤立监控策略")
  void shouldRejectMissingDefinitionBeforeWriting() {
    when(tenantGuard.resolveTenant(TENANT)).thenReturn(TENANT);
    WorkflowMonitoringPolicyRequest request = new WorkflowMonitoringPolicyRequest();
    request.setTenantId(TENANT);

    assertThatThrownBy(() -> service.save(DEFINITION_ID, request)).isInstanceOf(BizException.class);

    verify(policyMapper, never()).upsert(any());
  }

  @Test
  @DisplayName("策略写入后无法读回时不能返回保存成功")
  void shouldRejectSaveWhenImmediateReadBackIsUnavailable() {
    existingDefinition();
    WorkflowMonitoringPolicyRequest request = new WorkflowMonitoringPolicyRequest();
    request.setTenantId(TENANT);
    request.setSoftRuntimeSeconds(600);
    request.setStartGraceSeconds(120);
    when(policyMapper.selectByDefinition(TENANT, DEFINITION_ID)).thenReturn(null);

    assertThatThrownBy(() -> service.save(DEFINITION_ID, request))
        .isInstanceOf(BizException.class)
        .satisfies(exception -> {
          BizException failure = (BizException) exception;
          assertThat(failure.getCode()).isEqualTo(ResultCode.SYSTEM_ERROR);
          assertThat(failure.getMessageKey())
              .isEqualTo("error.workflow.monitoring_policy_not_persisted");
        });

    verify(policyMapper).upsert(any(WorkflowMonitoringPolicyEntity.class));
  }

  @Test
  @DisplayName("租户守卫拒绝后，不访问另一租户的策略")
  void shouldRejectCrossTenantReadBeforeDatabaseLookup() {
    when(tenantGuard.resolveTenant("tenant-b"))
        .thenThrow(BizException.of(ResultCode.FORBIDDEN, "error.common.forbidden"));

    assertThatThrownBy(() -> service.get(DEFINITION_ID, "tenant-b"))
        .isInstanceOf(BizException.class);

    verify(policyMapper, never()).selectByDefinition(any(), any());
  }

  private void existingDefinition() {
    when(tenantGuard.resolveTenant(TENANT)).thenReturn(TENANT);
    WorkflowDefinitionEntity definition = new WorkflowDefinitionEntity();
    definition.setId(DEFINITION_ID);
    definition.setTenantId(TENANT);
    when(definitionMapper.selectById(TENANT, DEFINITION_ID)).thenReturn(definition);
  }
}
