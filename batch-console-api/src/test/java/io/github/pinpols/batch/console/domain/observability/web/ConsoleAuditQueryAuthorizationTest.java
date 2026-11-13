package io.github.pinpols.batch.console.domain.observability.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.access.prepost.PreAuthorize;

@DisplayName("审计查询接口:与菜单一致只对管理员、租户管理员和审计员开放")
class ConsoleAuditQueryAuthorizationTest {

  @ParameterizedTest(name = "{0}#{1}")
  @CsvSource({
    "ConsoleQueryController, audits",
    "ConsoleQueryController, operationAudits",
    "ConsoleReportExcelController, audits"
  })
  @DisplayName("审计日志、操作审计及其导出排除普通租户用户")
  void shouldExcludeTenantUser_whenReadingAudits(String controller, String methodName)
      throws ClassNotFoundException {
    Class<?> type = Class.forName(getClass().getPackageName() + "." + controller);
    Method endpoint = Arrays.stream(type.getDeclaredMethods())
        .filter(method -> method.getName().equals(methodName))
        .findFirst()
        .orElseThrow();

    PreAuthorize authorization = endpoint.getAnnotation(PreAuthorize.class);

    assertThat(authorization).as(controller + "#" + methodName).isNotNull();
    assertThat(authorization.value())
        .isEqualTo(ConsoleSecurityExpressions.ADMIN_OR_AUDITOR_OR_TENANT_ADMIN);
  }
}
