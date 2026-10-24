package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.ScheduledOccurrenceCatchUpResponse;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.ops.ConsoleJobOperationsPort;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDefinitionService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDefinitionService.DagValidationResult;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowDefinitionDetailResponse;
import io.github.pinpols.batch.console.shared.command.ApprovalSubmitContext;
import io.github.pinpols.batch.console.shared.command.ConsoleCatchUpApprovalRequest;
import io.github.pinpols.batch.console.shared.command.ConsoleScheduledOccurrenceCatchUpCommand;
import io.github.pinpols.batch.console.support.web.ConsoleIdempotencyStore;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.console.security.single-session-enabled=false",
      "batch.console.ai.enabled=false"
    })
@Import(ConsoleCsrfCookieIntegrationTest.SecurityMode.class)
@DisplayName("控制台 CSRF 防护链路: 读请求的 Cookie 续期口径与写请求的请求头校验")
class ConsoleCsrfCookieIntegrationTest extends AbstractIntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMode {
    @Bean
    static BeanPostProcessor requireCsrf() {
      // 共享集成测试基类在属性绑定后开启 bypass；此用例必须走真实 CSRF 过滤链。
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

  private final BatchSecurityProperties securityProperties;

  private final ConsoleIdempotencyStore idempotencyStore;

  @MockitoBean
  private ConsoleJobOperationsPort jobOperations;

  @MockitoBean
  private WorkflowDefinitionService workflowDefinitionService;

  private WebTestClient client;

  @Autowired
  ConsoleCsrfCookieIntegrationTest(
      ConsoleJwtService jwtService,
      BatchSecurityProperties securityProperties,
      ConsoleIdempotencyStore idempotencyStore) {
    this.jwtService = jwtService;
    this.securityProperties = securityProperties;
    this.idempotencyStore = idempotencyStore;
  }

  @BeforeEach
  void setUp() {
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(30))
        .build();
  }

  @Test
  @DisplayName("已带 Cookie 的读请求不再下发新 Cookie,写请求缺请求头被拒,补齐后落回方法不允许")
  void shouldKeepCsrfCookieOnReadsAndRequireHeaderOnWrites_whenCsrfEnabled() {
    assertThat(securityProperties.isBypassMode()).isFalse();
    String jwt = jwtService
        .issueToken("csrf-test-admin", "system", Set.of("ROLE_ADMIN"), 0L)
        .accessToken();

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
    String csrfToken = csrfCookie.getValue();
    assertThat(csrfToken).isNotBlank();

    ResponseCookie afterRead = client
        .get()
        .uri("/api/console/auth/check")
        .cookie("batch_console_token", jwt)
        .cookie("XSRF-TOKEN", csrfToken)
        .exchange()
        .expectStatus()
        .isNoContent()
        .returnResult(Void.class)
        .getResponseCookies()
        .getFirst("XSRF-TOKEN");
    assertThat(afterRead).isNull();

    client
        .post()
        .uri("/api/console/queries/usage-summary")
        .cookie("batch_console_token", jwt)
        .cookie("XSRF-TOKEN", csrfToken)
        .exchange()
        .expectStatus()
        .isForbidden();

    client
        .post()
        .uri("/api/console/queries/usage-summary")
        .cookie("batch_console_token", jwt)
        .cookie("XSRF-TOKEN", csrfToken)
        .header("X-XSRF-TOKEN", csrfToken)
        .exchange()
        .expectStatus()
        .isEqualTo(405);
  }

  @Test
  @DisplayName("真实认证请求重复提交配置包上传时由类级幂等声明返回 409")
  void shouldRejectCompletedIdempotencyKeyOnTenantPackageUpload_whenAuthenticated() {
    String tenantId = "idempotency-http-tenant";
    String uri = "/api/console/config/tenant-package/excel/apply/upload-123";
    String idempotencyKey = "completed-upload-key";
    String redisKey = "console:idempotency:" + tenantId + ":POST:" + uri + ":" + idempotencyKey;
    String jwt = jwtService
        .issueToken("config-idempotency-admin", tenantId, Set.of("ROLE_TENANT_ADMIN"))
        .accessToken();
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
    assertThat(idempotencyStore.setIfAbsent(redisKey, "DONE", Duration.ofMinutes(1)))
        .isTrue();

    client
        .post()
        .uri(uri)
        .contentType(MediaType.APPLICATION_JSON)
        .cookie("batch_console_token", jwt)
        .cookie("XSRF-TOKEN", csrfCookie.getValue())
        .header("X-XSRF-TOKEN", csrfCookie.getValue())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.message")
        .value(message -> assertThat(message.toString()).contains("duplicate request"));
  }

  @Test
  @DisplayName("Tenant Admin 可经真实认证 HTTP 提交精确 occurrence 补跑,普通 Tenant User 被拒")
  void shouldAuthorizeScheduledOccurrenceCatchUpForTenantAdminOnly() {
    String tenantId = "occurrence-catch-up-tenant";
    String adminJwt = jwtService
        .issueToken("occurrence-operator", tenantId, Set.of("ROLE_TENANT_ADMIN"))
        .accessToken();
    String csrfToken = csrfToken(adminJwt);
    Instant scheduledFireTime = Instant.parse("2026-10-14T09:30:00Z");
    when(jobOperations.resolveTenant(tenantId)).thenReturn(tenantId);
    when(workflowDefinitionService.getById(23L, tenantId)).thenReturn(enabledWorkflow(tenantId));
    when(workflowDefinitionService.validate(23L, tenantId))
        .thenReturn(new DagValidationResult(true, List.of(), List.of()));
    when(jobOperations.delegateScheduledOccurrenceCatchUp(any()))
        .thenReturn(new ScheduledOccurrenceCatchUpResponse("request-42", "PENDING_APPROVAL"));
    when(jobOperations.submitApproval(any())).thenReturn("approval-42");

    client
        .post()
        .uri("/api/console/workflow-definitions/23/scheduled-occurrences/catch-up")
        .cookie("batch_console_token", adminJwt)
        .cookie("XSRF-TOKEN", csrfToken)
        .header("X-XSRF-TOKEN", csrfToken)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "occurrence-catch-up-key")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"tenantId":"occurrence-catch-up-tenant",
             "scheduledFireTime":"2026-10-14T09:30:00Z",
             "bizDate":"2026-10-14",
             "reason":"Recover rejected scheduled fire"}
            """)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.requestId")
        .isEqualTo("request-42")
        .jsonPath("$.data.status")
        .isEqualTo("PENDING_APPROVAL")
        .jsonPath("$.data.approvalId")
        .isEqualTo("approval-42");

    ArgumentCaptor<ConsoleScheduledOccurrenceCatchUpCommand> commandCaptor =
        ArgumentCaptor.forClass(ConsoleScheduledOccurrenceCatchUpCommand.class);
    verify(jobOperations).delegateScheduledOccurrenceCatchUp(commandCaptor.capture());
    assertThat(commandCaptor.getValue())
        .isEqualTo(new ConsoleScheduledOccurrenceCatchUpCommand(
            tenantId,
            23L,
            scheduledFireTime,
            LocalDate.of(2026, 10, 14),
            "occurrence-operator",
            "Recover rejected scheduled fire",
            "occurrence-catch-up-key"));
    ArgumentCaptor<ApprovalSubmitContext> approvalCaptor =
        ArgumentCaptor.forClass(ApprovalSubmitContext.class);
    verify(jobOperations).submitApproval(approvalCaptor.capture());
    assertThat(approvalCaptor.getValue().approvalType()).isEqualTo("CATCH_UP");
    assertThat(approvalCaptor.getValue().targetId()).isEqualTo("request-42");
    assertThat(approvalCaptor.getValue().idempotencyKey())
        .startsWith("scheduled-occurrence-approval:");
    assertThat(approvalCaptor.getValue().payload())
        .isInstanceOf(ConsoleCatchUpApprovalRequest.class);
    ConsoleCatchUpApprovalRequest approvalPayload =
        (ConsoleCatchUpApprovalRequest) approvalCaptor.getValue().payload();
    assertThat(approvalPayload.getWorkflowDefinitionId()).isEqualTo(23L);
    assertThat(approvalPayload.getRequestId()).isEqualTo("request-42");
    assertThat(approvalPayload.getBizDate()).isEqualTo("2026-10-14");
    assertThat(approvalPayload.getReason()).isEqualTo("Recover rejected scheduled fire");

    clearInvocations(jobOperations, workflowDefinitionService);
    String userJwt = jwtService
        .issueToken("occurrence-user", tenantId, Set.of("ROLE_TENANT_USER"))
        .accessToken();
    String userCsrfToken = csrfToken(userJwt);
    client
        .post()
        .uri("/api/console/workflow-definitions/23/scheduled-occurrences/catch-up")
        .cookie("batch_console_token", userJwt)
        .cookie("XSRF-TOKEN", userCsrfToken)
        .header("X-XSRF-TOKEN", userCsrfToken)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "unauthorized-occurrence-catch-up")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"tenantId":"occurrence-catch-up-tenant",
             "scheduledFireTime":"2026-10-14T09:30:00Z",
             "bizDate":"2026-10-14",
             "reason":"Unauthorized attempt"}
            """)
        .exchange()
        .expectStatus()
        .isForbidden();
    verify(workflowDefinitionService, never()).getById(any(), any());
    verify(jobOperations, never()).delegateScheduledOccurrenceCatchUp(any());
    verify(jobOperations, never()).submitApproval(any());
  }

  private WorkflowDefinitionDetailResponse enabledWorkflow(String tenantId) {
    return new WorkflowDefinitionDetailResponse(
        23L,
        tenantId,
        "DAILY_IMPORT",
        "Daily import",
        "BATCH",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        4,
        true,
        null,
        null,
        null,
        List.of(),
        List.of());
  }

  private String csrfToken(String jwt) {
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
    return csrfCookie.getValue();
  }
}
