package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalSubmitCommand;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.e2e.apps.E2eOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.governance.ApprovalWorkflowService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    classes = E2eOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApprovalSourceReconciliationE2eIT.InternalSecurity.class)
@DisplayName("审批来源回查：真实服务与 Console 客户端联合验证")
class ApprovalSourceReconciliationE2eIT extends AbstractIntegrationTest {

  private static final String SECRET = UUID.randomUUID().toString();

  @TestConfiguration(proxyBeanMethods = false)
  static class InternalSecurity {
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

  @Autowired
  private ApprovalWorkflowService approvals;

  @LocalServerPort
  private int port;

  @Test
  @DisplayName("审批决定后按原身份回查；不同载荷、其他租户或缺失记录不能确认")
  void shouldReconcileOnlyTheOriginalSubmissionOverAuthenticatedHttp() {
    String key = "reconcile-" + UUID.randomUUID();
    String payload = "{\"jobCode\":\"JOB_A\",\"bizDate\":\"2026-10-29\"}";
    var target = new ApprovalWorkflowService.ApprovalTarget(
        "CATCH_UP", "CATCH_UP", "CATCH_UP", null, payload);
    var source = new ApprovalWorkflowService.ApprovalSource("operator", null, key, "original");
    String approvalNo =
        approvals.submit(ApprovalWorkflowService.ApprovalSubmitCommand.of("t1", target, source));
    approvals.approve("t1", approvalNo, "reviewer", "approved");

    RestClient http = RestClient.builder()
        .baseUrl("http://127.0.0.1:" + port)
        .defaultHeader("X-Internal-Secret", SECRET)
        .build();
    OrchestratorInternalRestClient internal = mock(OrchestratorInternalRestClient.class);
    when(internal.client()).thenReturn(http);
    var client =
        new OrchestratorApprovalClient(internal, mock(ConsoleRequestMetadataResolver.class));
    var expected = ApprovalSubmitCommand.builder()
        .tenantId("t1")
        .approvalType("CATCH_UP")
        .actionType("CATCH_UP")
        .targetType("CATCH_UP")
        .payloadJson(payload)
        .requesterId("operator")
        .approvalReason("original")
        .idempotencyKey(key)
        .build();

    var found = client.lookupVerifiedSubmission(expected);
    assertThat(found.approvalNo()).isEqualTo(approvalNo);
    assertThat(found.sourceApprovalReason()).isEqualTo("original");
    assertThat(found.approvalReason()).isEqualTo("approved");
    var wrong = ApprovalSubmitCommand.builder()
        .tenantId("t1")
        .approvalType("CATCH_UP")
        .actionType("CATCH_UP")
        .targetType("CATCH_UP")
        .payloadJson("{\"jobCode\":\"OTHER\"}")
        .requesterId("operator")
        .approvalReason("original")
        .idempotencyKey(key)
        .build();
    assertThatThrownBy(() -> client.lookupVerifiedSubmission(wrong))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(() -> client.lookupBySourceKey("t2", key))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
    assertThatThrownBy(() -> client.lookupBySourceKey("t1", "missing-" + UUID.randomUUID()))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
    RestClient anonymous =
        RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    assertThatThrownBy(() -> anonymous
            .get()
            .uri("/internal/approvals/by-source-key?tenantId=t1")
            .header("Idempotency-Key", key)
            .retrieve()
            .toBodilessEntity())
        .isInstanceOf(HttpClientErrorException.Unauthorized.class);
  }
}
