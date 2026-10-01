package io.github.pinpols.batch.console.domain.observability.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class ConsoleUsageSummaryControllerTest {

  @Test
  void controllerExcludesTenantUserFromUsageSummary() {
    PreAuthorize authorization =
        ConsoleUsageSummaryController.class.getAnnotation(PreAuthorize.class);

    assertThat(authorization).isNotNull();
    assertThat(authorization.value())
        .isEqualTo(ConsoleSecurityExpressions.ADMIN_OR_AUDITOR_OR_TENANT_ADMIN);
  }
}
