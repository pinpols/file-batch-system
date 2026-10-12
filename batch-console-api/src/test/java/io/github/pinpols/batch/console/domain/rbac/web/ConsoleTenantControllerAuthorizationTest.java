package io.github.pinpols.batch.console.domain.rbac.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

@DisplayName("租户接口访问角色约束")
class ConsoleTenantControllerAuthorizationTest {

  @Test
  @DisplayName("租户列表允许审计员读取,但详情和写操作权限不扩展")
  void shouldLimitAuditorAccessToTenantListing() throws NoSuchMethodException {
    PreAuthorize listAuthorization = ConsoleTenantController.class
        .getMethod("list", String.class, String.class, int.class, int.class)
        .getAnnotation(PreAuthorize.class);
    PreAuthorize detailAuthorization = ConsoleTenantController.class
        .getMethod("get", String.class)
        .getAnnotation(PreAuthorize.class);
    PreAuthorize suspendAuthorization = ConsoleTenantController.class
        .getMethod("suspend", String.class)
        .getAnnotation(PreAuthorize.class);

    assertThat(listAuthorization.value())
        .isEqualTo(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN_OR_AUDITOR);
    assertThat(detailAuthorization.value())
        .isEqualTo(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN);
    assertThat(suspendAuthorization.value()).isEqualTo(ConsoleSecurityExpressions.ADMIN_ONLY);
  }
}
