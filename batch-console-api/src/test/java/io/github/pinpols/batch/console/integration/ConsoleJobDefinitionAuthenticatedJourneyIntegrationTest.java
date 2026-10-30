package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.console.security.single-session-enabled=false")
@Import(ConsoleJobDefinitionAuthenticatedJourneyIntegrationTest.SecurityMode.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("真实认证用户旅程：四类角色的作业创建授权边界")
class ConsoleJobDefinitionAuthenticatedJourneyIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT_ID = "int-ta";

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

  private final ConsoleJwtService jwtService;

  private final JdbcTemplate jdbcTemplate;

  ConsoleJobDefinitionAuthenticatedJourneyIntegrationTest(
      ConsoleJwtService jwtService, JdbcTemplate jdbcTemplate) {
    this.jwtService = jwtService;
    this.jdbcTemplate = jdbcTemplate;
  }

  @BeforeEach
  void ensureTenantCalendarFixture() {
    TestTenantCalendarFixtures.ensureDefaultCalendar(jdbcTemplate, TENANT_ID);
  }

  @Test
  @DisplayName("ADMIN/TENANT_ADMIN 可创建；AUDITOR/TENANT_USER 被拒且不产生作业定义")
  void shouldEnforceJobDefinitionCreatePermissionsForAuthenticatedRoles() {
    WebTestClient client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(60))
        .build();
    String suffix = Long.toUnsignedString(System.nanoTime(), 36);
    String adminJobCode = "auth_journey_admin_" + suffix;
    String tenantAdminJobCode = "auth_journey_tenant_admin_" + suffix;
    String auditorJobCode = "auth_journey_auditor_" + suffix;
    String tenantUserJobCode = "auth_journey_tenant_user_" + suffix;

    try {
      createJob(client, "journey-admin-" + suffix, "system", "ROLE_ADMIN", adminJobCode);
      createJob(
          client,
          "journey-tenant-admin-" + suffix,
          TENANT_ID,
          "ROLE_TENANT_ADMIN",
          tenantAdminJobCode);
      rejectCreateJob(client, "journey-auditor-" + suffix, "ROLE_AUDITOR", auditorJobCode);
      rejectCreateJob(
          client, "journey-tenant-user-" + suffix, "ROLE_TENANT_USER", tenantUserJobCode);

      assertThat(jobCount(adminJobCode)).isEqualTo(1);
      assertThat(jobCount(tenantAdminJobCode)).isEqualTo(1);
      assertThat(jobCount(auditorJobCode)).isZero();
      assertThat(jobCount(tenantUserJobCode)).isZero();
      assertThat(successfulCreateAuditCount()).isEqualTo(2);
    } finally {
      deleteJobDefinitions("job_code like ?", "auth_journey_%_" + suffix);
    }
  }

  private void createJob(
      WebTestClient client, String username, String principalTenant, String role, String jobCode) {
    AuthenticatedClient auth = authenticatedClient(client, username, principalTenant, role);
    client
        .post()
        .uri("/api/console/job-definitions")
        .cookie("batch_console_token", auth.jwt())
        .cookie("XSRF-TOKEN", auth.csrfToken())
        .header("X-XSRF-TOKEN", auth.csrfToken())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT_ID)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBody(jobCode))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(body -> assertThat(body).contains("\"jobCode\":\"" + jobCode + "\""));
  }

  private void rejectCreateJob(WebTestClient client, String username, String role, String jobCode) {
    AuthenticatedClient auth = authenticatedClient(client, username, TENANT_ID, role);
    client
        .post()
        .uri("/api/console/job-definitions")
        .cookie("batch_console_token", auth.jwt())
        .cookie("XSRF-TOKEN", auth.csrfToken())
        .header("X-XSRF-TOKEN", auth.csrfToken())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT_ID)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBody(jobCode))
        .exchange()
        .expectStatus()
        .isForbidden();
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

  private long jobCount(String jobCode) {
    String countJobDefinitionsSql = """
        select count(*) from batch.job_definition where tenant_id = ? and job_code = ?
    """;
    return jdbcTemplate.queryForObject(countJobDefinitionsSql, Long.class, TENANT_ID, jobCode);
  }

  private void deleteJobDefinitions(String jobPredicate, Object... parameters) {
    String jobIdsSql = """
        SELECT id FROM batch.job_definition WHERE %s
        """.formatted(jobPredicate);
    String workflowIdsSql = """
        SELECT id FROM batch.workflow_definition
         WHERE single_job_definition_id IN (%s)
        """.formatted(jobIdsSql);
    String deleteWorkflowEdgesSql = """
        DELETE FROM batch.workflow_edge WHERE workflow_definition_id IN (%s)
        """.formatted(workflowIdsSql);
    jdbcTemplate.update(deleteWorkflowEdgesSql, parameters);
    String deleteWorkflowNodesSql = """
        DELETE FROM batch.workflow_node WHERE workflow_definition_id IN (%s)
        """.formatted(workflowIdsSql);
    jdbcTemplate.update(deleteWorkflowNodesSql, parameters);
    String deleteWorkflowVersionsSql = """
        DELETE FROM batch.workflow_definition_version
         WHERE workflow_definition_id IN (%s)
        """.formatted(workflowIdsSql);
    jdbcTemplate.update(deleteWorkflowVersionsSql, parameters);
    String deleteWorkflowDefinitionsSql = """
        DELETE FROM batch.workflow_definition WHERE single_job_definition_id IN (%s)
        """.formatted(jobIdsSql);
    jdbcTemplate.update(deleteWorkflowDefinitionsSql, parameters);
    String deleteJobDefinitionsSql = """
        DELETE FROM batch.job_definition WHERE %s
        """.formatted(jobPredicate);
    jdbcTemplate.update(deleteJobDefinitionsSql, parameters);
  }

  private long successfulCreateAuditCount() {
    String countSuccessfulCreateAuditsSql = """
        select count(*) from batch.console_operation_audit
         where tenant_id = ?
           and aggregate_type = 'job_definition'
           and action = 'jobDefinition.create'
           and result = 'SUCCESS'
        """;
    return jdbcTemplate.queryForObject(countSuccessfulCreateAuditsSql, Long.class, TENANT_ID);
  }

  private static String createBody(String jobCode) {
    return """
        {"tenantId":"%s","jobCode":"%s","jobName":"Authentication journey",
        "jobType":"GENERAL","scheduleType":"MANUAL"}
        """.formatted(TENANT_ID, jobCode);
  }

  private record AuthenticatedClient(String jwt, String csrfToken) {}
}
