package io.github.pinpols.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.ops.dto.TaskExecutionAuditResponse;
import io.github.pinpols.batch.console.domain.ops.dto.TaskHeartbeatDetailsResponse;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleTaskExecutionAuditService;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleTaskHeartbeatService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.shared.web.ConsoleResponseFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

@ExtendWith(MockitoExtension.class)
@DisplayName("任务心跳接口:先解析租户再读取心跳详情, 返回任务标识与状态")
class ConsoleTaskControllerTest {

  @Mock
  private ConsoleTaskHeartbeatService heartbeatService;

  @Mock
  private ConsoleTaskExecutionAuditService executionAuditService;

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @Mock
  private ConsoleResponseFactory responseFactory;

  @InjectMocks
  private ConsoleTaskController controller;

  @Test
  @DisplayName("心跳详情:先解析租户再查询, 返回任务标识与状态")
  void shouldResolveTenantThenReturnDetails_whenReadingHeartbeat() {
    TaskHeartbeatDetailsResponse dto =
        new TaskHeartbeatDetailsResponse(42L, "RUNNING", null, null, false);
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    when(heartbeatService.getHeartbeatDetails("tx", 42L)).thenReturn(dto);
    when(responseFactory.success(dto)).thenReturn(CommonResponse.success(dto));

    assertThat(controller.heartbeatDetails(42L, "tx").data().taskId()).isEqualTo(42L);
  }

  @Test
  @DisplayName("执行审计:先解析租户再查询, 返回任务标识")
  void shouldResolveTenantThenReturnAudit_whenReadingExecutionAudit() {
    TaskExecutionAuditResponse dto =
        new TaskExecutionAuditResponse(7L, 70L, "JOB", "SUCCESS", null, null);
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    when(executionAuditService.getExecutionAudit("tx", 7L)).thenReturn(dto);
    when(responseFactory.success(dto)).thenReturn(CommonResponse.success(dto));

    assertThat(controller.executionAudit(7L, "tx").data().taskId()).isEqualTo(7L);
  }

  @Test
  @DisplayName("执行审计权限:只对平台管理员和审计员开放, 覆盖类级租户管理员角色")
  void shouldRestrictExecutionAuditToAdminAndAuditor() throws NoSuchMethodException {
    PreAuthorize authorization = ConsoleTaskController.class
        .getMethod("executionAudit", Long.class, String.class)
        .getAnnotation(PreAuthorize.class);

    assertThat(authorization).isNotNull();
    assertThat(authorization.value())
        .isEqualTo(ConsoleSecurityExpressions.ADMIN_OR_AUDITOR)
        .doesNotContain(ConsoleRoles.TENANT_ADMIN, ConsoleRoles.TENANT_USER);
  }
}
