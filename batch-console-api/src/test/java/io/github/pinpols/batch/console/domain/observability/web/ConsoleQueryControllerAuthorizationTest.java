package io.github.pinpols.batch.console.domain.observability.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.application.contract.query.OutboxDeliveryLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.OutboxRetryLogQueryRequest;
import io.github.pinpols.batch.console.domain.audit.application.contract.query.OperationAuditQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.PendingCatchUpQueryRequest;
import io.github.pinpols.batch.console.domain.ops.web.realtime.ConsoleOutboxRealtimeController;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.access.prepost.PreAuthorize;

@DisplayName("综合查询接口:与菜单一致的访问角色")
class ConsoleQueryControllerAuthorizationTest {

  @Test
  @DisplayName("操作审计只对管理员、租户管理员和审计员开放，排除普通租户用户")
  void shouldRestrictOperationAuditsToAuditRoles() throws NoSuchMethodException {
    String expression = methodExpression("operationAudits", OperationAuditQueryRequest.class);

    assertThat(expression)
        .isEqualTo(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN_OR_AUDITOR)
        .contains(ConsoleRoles.ADMIN, ConsoleRoles.TENANT_ADMIN, ConsoleRoles.AUDITOR)
        .doesNotContain(ConsoleRoles.TENANT_USER);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"outboxRetries", "outboxDeliveries", "catchUpApprovals"})
  @DisplayName("Outbox 与补跑审批查询只对管理员和租户管理员开放")
  void shouldRestrictOperatorQueriesToAdminRoles(String method) throws NoSuchMethodException {
    Class<?> requestType =
        switch (method) {
          case "outboxRetries" -> OutboxRetryLogQueryRequest.class;
          case "outboxDeliveries" -> OutboxDeliveryLogQueryRequest.class;
          default -> PendingCatchUpQueryRequest.class;
        };

    assertOperatorOnly(methodExpression(method, requestType));
  }

  @Test
  @DisplayName("Outbox 实时订阅与列表查询使用同一角色约束")
  void shouldRestrictOutboxStreamsToAdminRoles() {
    PreAuthorize authorization =
        ConsoleOutboxRealtimeController.class.getAnnotation(PreAuthorize.class);

    assertThat(authorization).isNotNull();
    assertOperatorOnly(authorization.value());
  }

  private static void assertOperatorOnly(String expression) {
    assertThat(expression)
        .isEqualTo(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN)
        .doesNotContain(ConsoleRoles.AUDITOR, ConsoleRoles.TENANT_USER);
  }

  private static String methodExpression(String method, Class<?> requestType)
      throws NoSuchMethodException {
    PreAuthorize authorization = ConsoleQueryController.class
        .getMethod(method, requestType)
        .getAnnotation(PreAuthorize.class);
    assertThat(authorization).isNotNull();
    return authorization.value();
  }
}
