package io.github.pinpols.batch.console.domain.observability.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.audit.application.contract.query.OperationAuditQueryRequest;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

@DisplayName("综合查询接口:与菜单一致的访问角色")
class ConsoleQueryControllerAuthorizationTest {

  @Test
  @DisplayName("操作审计只对管理员、租户管理员和审计员开放，排除普通租户用户")
  void shouldRestrictOperationAuditsToAuditRoles() throws NoSuchMethodException {
    PreAuthorize authorization = ConsoleQueryController.class
        .getMethod("operationAudits", OperationAuditQueryRequest.class)
        .getAnnotation(PreAuthorize.class);

    assertThat(authorization).isNotNull();
    assertThat(authorization.value())
        .isEqualTo(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN_OR_AUDITOR)
        .contains(ConsoleRoles.ADMIN, ConsoleRoles.TENANT_ADMIN, ConsoleRoles.AUDITOR)
        .doesNotContain(ConsoleRoles.TENANT_USER);
  }
}
