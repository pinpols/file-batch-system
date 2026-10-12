package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.application.ops.response.ConsoleInstanceActionResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.console.security.single-session-enabled=false")
@Import(ConsoleInstanceAuthenticatedIdempotencyIntegrationTest.SecurityMode.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("实例操作认证与幂等: 权限拒绝、缺键拒绝、重复请求只执行一次")
class ConsoleInstanceAuthenticatedIdempotencyIntegrationTest extends AbstractIntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMode {
    @Bean
    static BeanPostProcessor requireAuthentication() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (bean instanceof BatchSecurityProperties security) {
            security.setBypassMode(false);
          }
          return bean;
        }
      };
    }
  }

  @LocalServerPort
  private int port;

  @MockitoBean
  private ConsoleOrchestratorPort orchestrator;

  private final ConsoleJwtService jwtService;
  private final JdbcTemplate jdbcTemplate;

  ConsoleInstanceAuthenticatedIdempotencyIntegrationTest(
      ConsoleJwtService jwtService, JdbcTemplate jdbcTemplate) {
    this.jwtService = jwtService;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  @DisplayName("只有 ADMIN 可取消；缺失幂等键拒绝，同键重放不重复调用或审计")
  void shouldEnforceRoleAndIdempotencyForInstanceCancel() {
    long instanceId = System.nanoTime();
    String tenantId = "int-ta";
    String suffix = Long.toUnsignedString(instanceId);
    String action = "instance.cancel";
    String idempotencyKey = "instance-cancel-" + suffix;
    WebTestClient client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(30))
        .build();

    try {
      jdbcTemplate.update(
          "delete from batch.console_operation_audit where action = ? and aggregate_id = ?",
          action,
          Long.toString(instanceId));

      AuthenticatedClient admin =
          authenticatedClient(client, "journey-admin-" + suffix, "ROLE_ADMIN");
      postCancel(client, instanceId, tenantId, admin, null).expectStatus().isBadRequest();
      verifyNoInteractions(orchestrator);

      when(orchestrator.instanceAction(instanceId, tenantId, "cancel"))
          .thenReturn(new ConsoleInstanceActionResponse(
              instanceId, "instance-" + suffix, "CANCEL_REQUESTED", 2));
      postCancel(client, instanceId, tenantId, admin, idempotencyKey)
          .expectStatus()
          .isOk();
      assertThat(auditCount(action, instanceId)).isEqualTo(1);

      postCancel(client, instanceId, tenantId, admin, idempotencyKey)
          .expectStatus()
          .isEqualTo(409);
      verify(orchestrator).instanceAction(instanceId, tenantId, "cancel");
      assertThat(auditCount(action, instanceId)).isEqualTo(1);

      clearInvocations(orchestrator);
      for (String role : Set.of("ROLE_AUDITOR", "ROLE_TENANT_ADMIN", "ROLE_TENANT_USER")) {
        AuthenticatedClient readOnly =
            authenticatedClient(client, "journey-" + role + suffix, role);
        postCancel(client, instanceId, tenantId, readOnly, "forbidden-" + role + suffix)
            .expectStatus()
            .isForbidden();
      }
      verifyNoInteractions(orchestrator);
      assertThat(auditCount(action, instanceId)).isEqualTo(1);
    } finally {
      jdbcTemplate.update(
          "delete from batch.console_operation_audit where action = ? and aggregate_id = ?",
          action,
          Long.toString(instanceId));
    }
  }

  private AuthenticatedClient authenticatedClient(
      WebTestClient client, String username, String role) {
    String jwt = jwtService.issueToken(username, "system", Set.of(role)).accessToken();
    ResponseCookie csrfCookie = client
        .get()
        .uri("/api/console/auth/check")
        .cookie("batch_console_token", jwt)
        .exchange()
        .expectStatus()
        .isNoContent()
        .returnResult(Void.class)
        .getResponseCookies()
        .getFirst("XSRF-TOKEN");
    assertThat(csrfCookie).isNotNull();
    return new AuthenticatedClient(jwt, csrfCookie.getValue());
  }

  private WebTestClient.ResponseSpec postCancel(
      WebTestClient client,
      long instanceId,
      String tenantId,
      AuthenticatedClient auth,
      String idempotencyKey) {
    var request = client
        .post()
        .uri("/api/console/instances/{id}/cancel?tenantId={tenantId}", instanceId, tenantId)
        .cookie("batch_console_token", auth.jwt())
        .cookie("XSRF-TOKEN", auth.csrfToken())
        .header("X-XSRF-TOKEN", auth.csrfToken())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId);
    if (idempotencyKey != null) {
      request.header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey);
    }
    return request.exchange();
  }

  private int auditCount(String action, long instanceId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from batch.console_operation_audit where action = ? and aggregate_id = ?",
        Integer.class,
        action,
        Long.toString(instanceId));
  }

  private record AuthenticatedClient(String jwt, String csrfToken) {}
}
