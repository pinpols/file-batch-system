package io.github.pinpols.batch.console.domain.audit.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("审计参数脱敏: 嵌套凭据识别与非法载荷兜底")
class AuditParamRedactorTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("递归脱敏嵌套凭据字段且保留业务字段")
  void shouldRecursivelyRedactCredentialsWithoutRemovingOperationalFields() {
    JsonNode result = AuditParamRedactor.toRedactedTree(
        objectMapper,
        Map.of(
            "request",
            Map.of(
                "username",
                "alice",
                "password",
                "plain-password",
                "credentials",
                Map.of("clientSecret", "plain-secret"),
                "items",
                List.of(Map.of("accessToken", "plain-token"))),
            "keyName",
            "reporting-key"));

    assertThat(result.at("/request/username").asText()).isEqualTo("alice");
    assertThat(result.at("/request/password").asText()).isEqualTo(AuditParamRedactor.REDACTED);
    assertThat(result.at("/request/credentials").asText()).isEqualTo(AuditParamRedactor.REDACTED);
    assertThat(result.at("/request/items/0/accessToken").asText())
        .isEqualTo(AuditParamRedactor.REDACTED);
    assertThat(result.get("keyName").asText()).isEqualTo("reporting-key");
  }

  @Test
  @DisplayName("历史详情脱敏, 非法载荷按失败关闭兜底")
  void shouldSanitizeHistoricalJsonAndFailClosedForInvalidPayload() {
    String sanitized = AuditParamRedactor.redactJson(
        objectMapper, "{\"request\":{\"new_password\":\"raw\",\"tenantId\":\"ta\"}}");

    assertThat(sanitized).contains("\"new_password\":\"[REDACTED]\"");
    assertThat(sanitized).contains("\"tenantId\":\"ta\"");
    assertThat(AuditParamRedactor.redactJson(objectMapper, "not-json"))
        .isEqualTo("{\"_redacted\":true,\"_reason\":\"invalid_audit_params\"}");
  }
}
