package io.github.pinpols.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.ops.dto.TaskHeartbeatDetailsResponse;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleTaskHeartbeatService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleTaskControllerTest {

  @Mock private ConsoleTaskHeartbeatService heartbeatService;
  @Mock private ConsoleTenantGuard tenantGuard;
  @Mock private ConsoleResponseFactory responseFactory;

  @InjectMocks private ConsoleTaskController controller;

  @Test
  void resolvesTenantThenReturnsDetails() {
    TaskHeartbeatDetailsResponse dto =
        new TaskHeartbeatDetailsResponse(42L, "RUNNING", null, null, false);
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    when(heartbeatService.getHeartbeatDetails("tx", 42L)).thenReturn(dto);
    when(responseFactory.success(dto)).thenReturn(CommonResponse.success(dto));

    assertThat(controller.heartbeatDetails(42L, "tx").data().taskId()).isEqualTo(42L);
  }
}
