package io.github.pinpols.batch.console.domain.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.domain.file.mapper.FilePipelineMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobInstanceMapper;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleTriggerProxyService;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsolePasswordHasher;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowRunMapper;
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
  @Mock private ConsoleTenantReadinessService readinessService;
  @Mock private io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard tenantGuard;

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
            readinessService,
            tenantGuard,
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

  // P1-5: 远端 trigger pause/resume 失败时,console DB 不能被改(否则状态分裂)。
  // 远端先于 DB 写调用,且 callOrThrow fail-fast,故抛异常时 updateStatus 不应被触达。

  @Test
  void suspendTenant_whenRemotePauseFails_doesNotUpdateStatus() {
    when(tenantMapper.selectByTenantId("tenant-a")).thenReturn(ACTIVE_TENANT);
    when(jobInstanceMapper.countByStatuses(eq("tenant-a"), any())).thenReturn(0L);
    when(filePipelineMapper.countByStatuses(eq("tenant-a"), any())).thenReturn(0L);
    when(workflowRunMapper.countByStatuses(eq("tenant-a"), any())).thenReturn(0L);
    when(triggerProxyService.pauseByTenant("tenant-a"))
        .thenThrow(
            BizException.of(
                io.github.pinpols.batch.common.enums.ResultCode.SERVICE_UNAVAILABLE,
                "error.common.downstream_unavailable",
                "trigger"));

    assertThatThrownBy(() -> service.suspendTenant("tenant-a")).isInstanceOf(BizException.class);

    verify(triggerProxyService).pauseByTenant("tenant-a");
    verify(tenantMapper, never()).updateStatus(any(), any());
  }

  @Test
  void activateTenant_whenRemoteResumeFails_doesNotUpdateStatus() {
    when(tenantMapper.selectByTenantId("tenant-a")).thenReturn(ACTIVE_TENANT);
    when(triggerProxyService.resumeByTenant("tenant-a"))
        .thenThrow(
            BizException.of(
                io.github.pinpols.batch.common.enums.ResultCode.SERVICE_UNAVAILABLE,
                "error.common.downstream_unavailable",
                "trigger"));

    assertThatThrownBy(() -> service.activateTenant("tenant-a")).isInstanceOf(BizException.class);

    verify(triggerProxyService).resumeByTenant("tenant-a");
    verify(tenantMapper, never()).updateStatus(any(), any());
  }

  private static final Map<String, Object> ACME_TENANT =
      Map.of(
          "id", 9L,
          "tenant_id", "acme",
          "tenant_name", "Acme",
          "status", "ACTIVE",
          "description", "",
          "created_by", "admin",
          "created_at", "2026-01-01T00:00:00",
          "updated_at", "2026-01-01T00:00:00");

  @Test
  void provisionTenant_withoutInitConfig_skipsCopyButRunsReadiness() {
    when(tenantMapper.selectByTenantId("acme")).thenReturn(null).thenReturn(ACME_TENANT);
    when(userAccountMapper.selectByUsername("acme-admin")).thenReturn(null);
    when(passwordHasher.encode(any())).thenReturn("hash");
    when(readinessService.check("acme"))
        .thenReturn(
            new io.github.pinpols.batch.console.domain.rbac.web.response.TenantReadinessResponse(
                "acme", true, List.of(), List.of()));

    var cmd =
        new ConsoleTenantApplicationService.CreateTenantCommand(
            "acme", "Acme", "d", "acme-admin", "pw12345678", "admin");
    var resp =
        service.provisionTenant(
            cmd, new ConsoleTenantApplicationService.ConfigInitOption(null, null));

    assertThat(resp.tenant().tenantId()).isEqualTo("acme");
    assertThat(resp.configInit()).isNull();
    assertThat(resp.readiness().ready()).isTrue();
    verify(configCopyService, never()).copy(any(), any(), any());
    verify(readinessService).check("acme");
  }

  // ── SEC-IDOR(S2):租户管理只读端点跨租户越权修复 ──────────────────────────────

  @org.junit.jupiter.api.Nested
  class TenantScopeOnRead {

    @Test
    void getTenant_crossTenant_deniedByGuard_neverQueries() {
      // arrange:守卫对越权 tenantId 抛 FORBIDDEN(等价 TENANT_ADMIN 读他租户)
      org.mockito.Mockito.doThrow(
              io.github.pinpols.batch.common.exception.BizException.of(
                  io.github.pinpols.batch.common.enums.ResultCode.FORBIDDEN,
                  "error.tenant.mismatch"))
          .when(tenantGuard)
          .assertTenantAllowed("tenant-b");

      // act / assert
      assertThatThrownBy(() -> service.getTenant("tenant-b"))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(io.github.pinpols.batch.common.enums.ResultCode.FORBIDDEN);
      verify(tenantMapper, never()).selectByTenantId("tenant-b");
    }

    @Test
    void getTenant_sameTenant_allowed_returnsRow() {
      // 守卫放行(same-tenant / 全局角色)→ 正常返回
      when(tenantMapper.selectByTenantId("tenant-a")).thenReturn(ACTIVE_TENANT);

      assertThat(service.getTenant("tenant-a").tenantId()).isEqualTo("tenant-a");
      verify(tenantGuard).assertTenantAllowed("tenant-a");
    }

    @Test
    void listTenants_tenantRole_scopedToOwnTenantOnly() {
      // 租户角色:currentTenantScopeOrNull 返回自身租户 → 只返回自身,不发全量查询
      when(tenantGuard.currentTenantScopeOrNull()).thenReturn("tenant-a");
      when(tenantMapper.selectByTenantId("tenant-a")).thenReturn(ACTIVE_TENANT);

      var page = service.listTenants(null, null, new PageRequest(1, 20));

      assertThat(page.items()).hasSize(1);
      assertThat(page.items().get(0).tenantId()).isEqualTo("tenant-a");
      verify(tenantMapper, never()).selectByQuery(any(), any(), any());
      verify(tenantMapper, never()).countByQuery(any(), any());
    }

    @Test
    void listTenants_globalRole_returnsFullQuery() {
      // 全局角色:scope 为 null → 走原全量分页查询
      when(tenantGuard.currentTenantScopeOrNull()).thenReturn(null);
      when(tenantMapper.selectByQuery(any(), any(), any()))
          .thenReturn(List.of(ACTIVE_TENANT, ACME_TENANT));
      when(tenantMapper.countByQuery(any(), any())).thenReturn(2L);

      var page = service.listTenants(null, null, new PageRequest(1, 20));

      assertThat(page.items()).hasSize(2);
      verify(tenantMapper).selectByQuery(any(), any(), any());
    }
  }

  @Test
  void provisionTenant_withInitConfig_copiesThenRunsReadiness() {
    when(tenantMapper.selectByTenantId("acme")).thenReturn(null).thenReturn(ACME_TENANT);
    when(userAccountMapper.selectByUsername("acme-admin")).thenReturn(null);
    when(passwordHasher.encode(any())).thenReturn("hash");
    when(configCopyService.copy(any(), eq("admin"), any())).thenReturn(null);
    when(readinessService.check("acme"))
        .thenReturn(
            new io.github.pinpols.batch.console.domain.rbac.web.response.TenantReadinessResponse(
                "acme", true, List.of(), List.of()));

    var cmd =
        new ConsoleTenantApplicationService.CreateTenantCommand(
            "acme", "Acme", "d", "acme-admin", "pw12345678", "admin");
    service.provisionTenant(
        cmd, new ConsoleTenantApplicationService.ConfigInitOption("default", null));

    verify(configCopyService).copy(any(), eq("admin"), any());
    verify(readinessService).check("acme");
  }
}
