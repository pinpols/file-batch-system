package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.governance.ApprovalWorkflowService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.security.bypass-mode=false",
      "batch.security.internal-secret=approval-lookup-test-secret-2026"
    })
@DisplayName("内部审批来源键查询的 HTTP 鉴权与租户边界")
@Import(ApprovalSourceLookupHttpIntegrationTest.SecurityMode.class)
class ApprovalSourceLookupHttpIntegrationTest extends AbstractIntegrationTest {

  private static final String SECRET = "approval-lookup-test-secret-2026";

  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMode {
    @Bean
    static BeanPostProcessor enableInternalAuthentication() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          if (bean instanceof BatchSecurityProperties security) {
            security.setBypassMode(false);
            security.setInternalSecret(SECRET);
          }
          return bean;
        }
      };
    }
  }

  private final ApprovalWorkflowService approvals;

  @LocalServerPort
  private int port;

  @Autowired
  ApprovalSourceLookupHttpIntegrationTest(ApprovalWorkflowService approvals) {
    this.approvals = approvals;
  }

  @Test
  @DisplayName("审批决定后按原键回查返回原提交原因和决定原因")
  void shouldReturnOriginalSubmissionReasonAfterDecision() {
    String key = "approval-decision-http-" + UUID.randomUUID();
    String approvalNo = approvals.submit(ApprovalWorkflowService.ApprovalSubmitCommand.of(
        "t1",
        new ApprovalWorkflowService.ApprovalTarget(
            "COMPENSATION", "DLQ_REPLAY", "DEAD_LETTER", "100", "{}"),
        new ApprovalWorkflowService.ApprovalSource("operator", null, key, "original reason")));
    approvals.approve("t1", approvalNo, "approver", "decision reason");
    RestClient client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    Response found = lookup(client, "t1", key, SECRET);
    assertThat(found.status()).isEqualTo(200);
    JsonNode record = JsonUtils.fromJson(found.body(), JsonNode.class).path("record");
    assertThat(record.path("sourceApprovalReason").asText()).isEqualTo("original reason");
    assertThat(record.path("approvalReason").asText()).isEqualTo("decision reason");
  }

  @Test
  @DisplayName("有效内部凭据按租户读取审批，无凭据和其他租户均不能取得记录")
  void shouldEnforceInternalAuthenticationAndTenantLookup() {
    String key = "approval-http-" + UUID.randomUUID();
    String approvalNo = approvals.submit(ApprovalWorkflowService.ApprovalSubmitCommand.of(
        "t1",
        new ApprovalWorkflowService.ApprovalTarget(
            "COMPENSATION", "DLQ_REPLAY", "DEAD_LETTER", "100", "{}"),
        new ApprovalWorkflowService.ApprovalSource("operator", null, key, null)));
    RestClient client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();

    Response anonymous = lookup(client, "t1", key, null);
    Response found = lookup(client, "t1", key, SECRET);
    Response otherTenant = lookup(client, "t2", key, SECRET);
    Response missingKey = lookup(client, "t1", null, SECRET);

    assertThat(anonymous.status()).isEqualTo(401);
    assertThat(found.status()).isEqualTo(200);
    JsonNode record = JsonUtils.fromJson(found.body(), JsonNode.class).path("record");
    assertThat(record.path("tenantId").asText()).isEqualTo("t1");
    assertThat(record.path("approvalNo").asText()).isEqualTo(approvalNo);
    assertThat(otherTenant.status()).isEqualTo(404);
    assertThat(otherTenant.body()).doesNotContain(approvalNo);
    assertThat(missingKey.status()).isEqualTo(400);
  }

  private Response lookup(RestClient client, String tenant, String key, String secret) {
    return client
        .get()
        .uri(builder -> builder
            .path("/internal/approvals/by-source-key")
            .queryParam("tenantId", tenant)
            .build())
        .headers(headers -> {
          if (key != null) {
            headers.set("Idempotency-Key", key);
          }
          if (secret != null) {
            headers.set("X-Internal-Secret", secret);
          }
        })
        .exchange((request, response) -> new Response(
            response.getStatusCode().value(),
            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
  }

  private record Response(int status, String body) {}
}
