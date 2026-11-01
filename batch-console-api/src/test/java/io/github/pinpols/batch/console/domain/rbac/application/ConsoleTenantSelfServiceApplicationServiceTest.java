package io.github.pinpols.batch.console.domain.rbac.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.config.ConsoleQuotaPolicyApplicationService;
import io.github.pinpols.batch.console.application.observability.ConsoleSystemParameterService;
import io.github.pinpols.batch.console.domain.rbac.application.contract.request.QuotaChangeRequest;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("租户自助应用服务：配额申请的安全序列化")
class ConsoleTenantSelfServiceApplicationServiceTest {
  @Mock
  private ConsoleQuotaPolicyApplicationService quotaService;

  @Mock
  private ConsoleSystemParameterService parameterService;

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @Mock
  private BatchDateTimeSupport timeSupport;

  @Mock
  private ConsoleRequestMetadataResolver metadataResolver;

  @Test
  @DisplayName("引号、反斜线和换行在存储的 JSON 中保持原值")
  void shouldSerializeQuotaRequestWithoutCorruptingUserText() throws Exception {
    when(timeSupport.currentEpochMillis()).thenReturn(123L);
    QuotaChangeRequest request = new QuotaChangeRequest("jobs\"\\", 12, "line1\nline2\\\"quoted");
    String operator = "user\"\\name";
    when(metadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("request", "trace", "tenant-a", operator, null, null));
    ConsoleTenantSelfServiceApplicationService service =
        new ConsoleTenantSelfServiceApplicationService(
            quotaService, parameterService, tenantGuard, timeSupport, metadataResolver);
    String key = service.requestQuotaChange("tenant-a", request);

    assertThat(key).isEqualTo("tenant.quota-request.123");
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(parameterService)
        .upsert(
            eq("tenant-a"),
            eq(key),
            payload.capture(),
            eq("Quota change request from " + operator),
            eq(operator));
    JsonNode json = new ObjectMapper().readTree(payload.getValue());
    assertThat(json.get("field").asText()).isEqualTo(request.field());
    assertThat(json.get("requestedValue").asInt()).isEqualTo(12);
    assertThat(json.get("reason").asText()).isEqualTo(request.reason());
    assertThat(json.get("operator").asText()).isEqualTo(operator);
  }

  @Test
  @DisplayName("缺省原因仍按历史格式保存为字符串 null")
  void shouldPreserveMissingReasonRepresentation() throws Exception {
    when(timeSupport.currentEpochMillis()).thenReturn(124L);
    when(metadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("request", "trace", "tenant-a", "user", null, null));
    ConsoleTenantSelfServiceApplicationService service =
        new ConsoleTenantSelfServiceApplicationService(
            quotaService, parameterService, tenantGuard, timeSupport, metadataResolver);
    service.requestQuotaChange("tenant-a", new QuotaChangeRequest("jobs", 1, null));
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(parameterService)
        .upsert(
            eq("tenant-a"),
            eq("tenant.quota-request.124"),
            payload.capture(),
            eq("Quota change request from user"),
            eq("user"));
    assertThat(new ObjectMapper().readTree(payload.getValue()).get("reason").asText())
        .isEqualTo("null");
  }
}
