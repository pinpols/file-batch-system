package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleAlertApplicationService;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleAlertActionResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.console.security.single-session-enabled=false")
@Import(ConsoleAlertAuthenticatedAuthorizationIntegrationTest.SecurityMode.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("告警认证与授权: 查询租户隔离,变更操作审计")
class ConsoleAlertAuthenticatedAuthorizationIntegrationTest extends AbstractIntegrationTest {

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

  private static final String ACTION = "alert.ack";
  private static final String COUNT_AUDIT_SQL = """
      select count(*) from batch.console_operation_audit where action = ? and aggregate_id = ?
      """;
  private static final String DELETE_AUDIT_SQL = """
      delete from batch.console_operation_audit where action = ? and aggregate_id = ?
      """;
  private static final String INSERT_ALERT_SQL = """
      insert into batch.alert_event (
          tenant_id, service_name, alert_type, severity, title, detail_json,
          dedup_fingerprint, occurrence_count, status
      ) values (?, 'batch-orchestrator', ?, 'WARN', ?, '{}', ?, 1, 'OPEN')
      """;
  private static final String DELETE_ALERTS_SQL = "delete from batch.alert_event where tenant_id in (?, ?)";

  @LocalServerPort
  private int port;

  @MockitoBean
  private ConsoleAlertApplicationService alertService;

  private final ConsoleJwtService jwtService;
  private final JdbcTemplate jdbcTemplate;

  ConsoleAlertAuthenticatedAuthorizationIntegrationTest(
      ConsoleJwtService jwtService, JdbcTemplate jdbcTemplate) {
    this.jwtService = jwtService;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  @DisplayName("租户管理员可确认告警; 租户用户与匿名请求不能触达应用服务")
  void shouldRequireAuthorizedSessionForAlertMutation() {
    long alertId = System.nanoTime();
    String tenantId = "alert-auth-" + Long.toUnsignedString(alertId);
    WebTestClient client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(30))
        .build();

    try {
      clearAudit(alertId);
      when(alertService.ack(anyLong(), any(), anyString()))
          .thenReturn(new ConsoleAlertActionResponse(alertId, tenantId, "ack", "ACKED"));

      AuthenticatedClient tenantAdmin = authenticatedClient(
          client, "alert-admin-" + Long.toUnsignedString(alertId), tenantId, "ROLE_TENANT_ADMIN");
      postAck(client, alertId, tenantId, tenantAdmin, "alert-ack-" + alertId)
          .expectStatus()
          .isOk();
      verify(alertService).ack(anyLong(), any(), anyString());
      assertThat(auditCount(alertId)).isEqualTo(1);

      clearInvocations(alertService);
      AuthenticatedClient viewer = authenticatedClient(
          client,
          "alert-user-" + Long.toUnsignedString(alertId),
          tenantId,
          "ROLE_TENANT_USER");
      postAck(client, alertId, tenantId, viewer, "alert-viewer-" + alertId)
          .expectStatus()
          .isForbidden();
      postAck(client, alertId, tenantId, null, "alert-anonymous-" + alertId)
          .expectStatus()
          .isForbidden();
      verifyNoInteractions(alertService);
      assertThat(auditCount(alertId)).isEqualTo(1);
    } finally {
      clearAudit(alertId);
    }
  }

  @Test
  @DisplayName("租户用户只能读取本租户告警; 伪造租户和匿名读取被拒绝")
  void shouldRestrictAlertReadsToAuthenticatedTenant() {
    long suffix = System.nanoTime();
    String tenantId = "alert-read-" + Long.toUnsignedString(suffix);
    String otherTenantId = tenantId + "-other";
    WebTestClient client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(30))
        .build();

    try {
      insertAlert(tenantId, "own-" + suffix, "own tenant alert");
      insertAlert(otherTenantId, "other-" + suffix, "other tenant alert");

      AuthenticatedClient tenantUser = authenticatedClient(
          client, "alert-reader-" + Long.toUnsignedString(suffix), tenantId, "ROLE_TENANT_USER");
      getAlerts(client, tenantId, tenantUser)
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.items")
          .exists()
          .jsonPath("$.data.total")
          .isEqualTo(1)
          .jsonPath("$.data.items[0].tenantId")
          .isEqualTo(tenantId)
          .jsonPath("$.data.items[0].title")
          .isEqualTo("own tenant alert");

      getAlerts(client, otherTenantId, tenantUser).expectStatus().isForbidden();
      getAlerts(client, tenantId, null).expectStatus().isUnauthorized();
    } finally {
      jdbcTemplate.update(DELETE_ALERTS_SQL, tenantId, otherTenantId);
    }
  }

  private AuthenticatedClient authenticatedClient(
      WebTestClient client, String username, String tenantId, String role) {
    String jwt = jwtService.issueToken(username, tenantId, Set.of(role)).accessToken();
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

  private WebTestClient.ResponseSpec postAck(
      WebTestClient client,
      long alertId,
      String tenantId,
      AuthenticatedClient authenticatedClient,
      String idempotencyKey) {
    Map<String, String> requestBody = Map.of(
        "tenantId", tenantId,
        "reason", "R27 authorization");
    var request = client
        .post()
        .uri("/api/console/alerts/{id}/ack", alertId)
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(requestBody);
    if (authenticatedClient != null) {
      request = request
          .cookie("batch_console_token", authenticatedClient.jwt())
          .cookie("XSRF-TOKEN", authenticatedClient.csrfToken())
          .header("X-XSRF-TOKEN", authenticatedClient.csrfToken());
    }
    return request.exchange();
  }

  private WebTestClient.ResponseSpec getAlerts(
      WebTestClient client, String tenantId, AuthenticatedClient authenticatedClient) {
    var request = client.get()
        .uri(uriBuilder -> uriBuilder
            .path("/api/console/queries/alerts")
            .queryParam("tenantId", tenantId)
            .build())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId);
    if (authenticatedClient != null) {
      request = request.cookie("batch_console_token", authenticatedClient.jwt());
    }
    return request.exchange();
  }

  private void insertAlert(String tenantId, String fingerprint, String title) {
    jdbcTemplate.update(
        INSERT_ALERT_SQL,
        tenantId,
        "R27_TEST_ALERT",
        title,
        fingerprint);
  }

  private int auditCount(long alertId) {
    return jdbcTemplate.queryForObject(
        COUNT_AUDIT_SQL,
        Integer.class,
        ACTION,
        Long.toString(alertId));
  }

  private void clearAudit(long alertId) {
    jdbcTemplate.update(
        DELETE_AUDIT_SQL,
        ACTION,
        Long.toString(alertId));
  }

  private record AuthenticatedClient(String jwt, String csrfToken) {}
}
