package io.github.pinpols.batch.console.domain.observability.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

@DisplayName("用量汇总接口:接口访问角色约束")
class ConsoleUsageSummaryControllerTest {

  @Test
  @DisplayName("用量汇总访问控制:仅管理员、审计员或租户管理员可访问,普通租户用户被排除")
  void shouldRequireAdminOrAuditorRole_whenAccessingUsageSummary() {
    PreAuthorize authorization =
        ConsoleUsageSummaryController.class.getAnnotation(PreAuthorize.class);

    assertThat(authorization).isNotNull();
    assertThat(authorization.value())
        .isEqualTo(ConsoleSecurityExpressions.ADMIN_OR_AUDITOR_OR_TENANT_ADMIN);
  }
}
