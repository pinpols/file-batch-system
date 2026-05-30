package com.example.batch.console.domain.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import com.example.batch.console.application.config.ConsoleTenantConfigCopyService;
import com.example.batch.console.domain.file.mapper.FilePipelineMapper;
import com.example.batch.console.domain.job.mapper.JobInstanceMapper;
import com.example.batch.console.domain.ops.application.ConsoleTriggerProxyService;
import com.example.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper;
import com.example.batch.console.domain.rbac.mapper.TenantMapper;
import com.example.batch.console.domain.rbac.support.ConsolePasswordHasher;
import com.example.batch.console.domain.workflow.mapper.WorkflowRunMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleTenantApplicationServiceTest {

  @Mock private TenantMapper tenantMapper;
  @Mock private ConsoleUserAccountMapper userAccountMapper;
  @Mock private ConsolePasswordHasher passwordHasher;
  @Mock private JobInstanceMapper jobInstanceMapper;
  @Mock private FilePipelineMapper filePipelineMapper;
  @Mock private WorkflowRunMapper workflowRunMapper;
  @Mock private ConsoleTriggerProxyService triggerProxyService;
  @Mock private ConsoleTenantConfigCopyService configCopyService;

  private final org.springframework.core.env.Environment environment =
      new org.springframework.mock.env.MockEnvironment();

  private ConsoleTenantApplicationService service;

  private static final Map<String, Object> ACTIVE_TENANT =
      Map.of(
          "id", 1L,
          "tenant_id", "tenant-a",
          "tenant_name", "Tenant A",
          "status", "ACTIVE",
          "description", "",
          "created_by", "admin",
          "created_at", "2026-01-01T00:00:00",
          "updated_at", "2026-01-01T00:00:00");

  @BeforeEach
  void setUp() {
    service =
        new ConsoleTenantApplicationService(
            tenantMapper,
            userAccountMapper,
            passwordHasher,
            jobInstanceMapper,
            filePipelineMapper,
            workflowRunMapper,
            triggerProxyService,
            configCopyService,
            environment);
  }

  @Test
  void suspendTenant_withActiveJobInstances_throwsBizException() {
    when(tenantMapper.selectByTenantId("tenant-a")).thenReturn(ACTIVE_TENANT);
    when(jobInstanceMapper.countByStatuses(eq("tenant-a"), any())).thenReturn(2L);
    when(filePipelineMapper.countByStatuses(eq("tenant-a"), any())).thenReturn(0L);
    when(workflowRunMapper.countByStatuses(eq("tenant-a"), any())).thenReturn(0L);

    assertThatThrownBy(() -> service.suspendTenant("tenant-a"))
        .isInstanceOf(BizException.class)
        // i18n: messageKey 不含原文,改用 messageArgs 检查
        .satisfies(
            ex -> {
              String joined = String.join(" | ", argsAsStrings((BizException) ex));
              assertThat(joined)
                  .contains("cannot suspend tenant with active instances")
                  .contains("jobs=2");
            });

    verify(tenantMapper, never()).updateStatus(any(), any());
  }

  @Test
  void suspendTenant_withActivePipelineInstances_throwsBizException() {
    when(tenantMapper.selectByTenantId("tenant-a")).thenReturn(ACTIVE_TENANT);
    when(jobInstanceMapper.countByStatuses(eq("tenant-a"), any())).thenReturn(0L);
    when(filePipelineMapper.countByStatuses(eq("tenant-a"), any())).thenReturn(3L);
    when(workflowRunMapper.countByStatuses(eq("tenant-a"), any())).thenReturn(0L);

    assertThatThrownBy(() -> service.suspendTenant("tenant-a"))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex ->
                assertThat(String.join(" | ", argsAsStrings((BizException) ex)))
                    .contains("pipelines=3"));

    verify(tenantMapper, never()).updateStatus(any(), any());
  }

  @Test
  void suspendTenant_withActiveWorkflowRuns_throwsBizException() {
    when(tenantMapper.selectByTenantId("tenant-a")).thenReturn(ACTIVE_TENANT);
    when(jobInstanceMapper.countByStatuses(eq("tenant-a"), any())).thenReturn(0L);
    when(filePipelineMapper.countByStatuses(eq("tenant-a"), any())).thenReturn(0L);
    when(workflowRunMapper.countByStatuses(eq("tenant-a"), any())).thenReturn(1L);

    assertThatThrownBy(() -> service.suspendTenant("tenant-a"))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex ->
                assertThat(String.join(" | ", argsAsStrings((BizException) ex)))
                    .contains("workflows=1"));

    verify(tenantMapper, never()).updateStatus(any(), any());
  }

  private static java.util.List<String> argsAsStrings(BizException ex) {
    Object[] args = ex.getMessageArgs();
    if (args == null) return java.util.List.of();
    java.util.List<String> result = new java.util.ArrayList<>(args.length);
    for (Object a : args) {
      result.add(a == null ? "null" : a.toString());
    }
    return result;
  }

  @Test
  void suspendTenant_noActiveInstances_proceedsSuspend() {
    when(tenantMapper.selectByTenantId("tenant-a")).thenReturn(ACTIVE_TENANT);
    when(jobInstanceMapper.countByStatuses(eq("tenant-a"), any())).thenReturn(0L);
    when(filePipelineMapper.countByStatuses(eq("tenant-a"), any())).thenReturn(0L);
    when(workflowRunMapper.countByStatuses(eq("tenant-a"), any())).thenReturn(0L);

    service.suspendTenant("tenant-a");

    verify(tenantMapper).updateStatus("tenant-a", "SUSPENDED");
    verify(triggerProxyService).pauseByTenant("tenant-a");
  }

  @Test
  void suspendTenant_countsCorrectActiveStatuses() {
    List<String> expectedJobStatuses =
        List.of("CREATED", "WAITING", "READY", "RUNNING", "PARTIAL_FAILED");
    List<String> expectedPipelineStatuses = List.of("CREATED", "RUNNING", "COMPENSATING");
    List<String> expectedWorkflowStatuses = List.of("CREATED", "RUNNING");

    when(tenantMapper.selectByTenantId("tenant-a")).thenReturn(ACTIVE_TENANT);
    when(jobInstanceMapper.countByStatuses("tenant-a", expectedJobStatuses)).thenReturn(0L);
    when(filePipelineMapper.countByStatuses("tenant-a", expectedPipelineStatuses)).thenReturn(0L);
    when(workflowRunMapper.countByStatuses("tenant-a", expectedWorkflowStatuses)).thenReturn(0L);

    service.suspendTenant("tenant-a");

    verify(jobInstanceMapper).countByStatuses("tenant-a", expectedJobStatuses);
    verify(filePipelineMapper).countByStatuses("tenant-a", expectedPipelineStatuses);
    verify(workflowRunMapper).countByStatuses("tenant-a", expectedWorkflowStatuses);
  }
}
