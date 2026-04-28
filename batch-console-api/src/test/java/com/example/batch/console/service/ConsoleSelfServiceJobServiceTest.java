package com.example.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.service.ConsoleSelfServiceJobService.CompensationParam;
import com.example.batch.console.service.ConsoleSelfServiceJobService.RerunParam;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

class ConsoleSelfServiceJobServiceTest {

  private RestClient.Builder restClientBuilder;
  private ConsoleOrchestratorClientProperties orchestratorClientProperties;
  private ConsoleTenantGuard tenantGuard;
  private Environment environment;
  private ConsoleSelfServiceJobService service;

  private RestClient restClient;
  private RestClient.RequestBodyUriSpec bodyUriSpec;
  private RestClient.RequestBodySpec bodySpec;
  private RestClient.ResponseSpec responseSpec;

  @BeforeEach
  void setUp() {
    restClientBuilder = mock(RestClient.Builder.class);
    orchestratorClientProperties = mock(ConsoleOrchestratorClientProperties.class);
    tenantGuard = mock(ConsoleTenantGuard.class);
    environment = mock(Environment.class);

    restClient = mock(RestClient.class);
    bodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
    bodySpec = mock(RestClient.RequestBodySpec.class);
    responseSpec = mock(RestClient.ResponseSpec.class);

    when(orchestratorClientProperties.getBaseUrl()).thenReturn("http://localhost:8080");
    when(environment.resolveRequiredPlaceholders(anyString())).thenReturn("http://localhost:8080");
    when(restClientBuilder.baseUrl(anyString())).thenReturn(restClientBuilder);
    when(restClientBuilder.build()).thenReturn(restClient);
    when(restClient.post()).thenReturn(bodyUriSpec);
    when(bodyUriSpec.uri(anyString())).thenReturn(bodySpec);
    // varargs: header(String, String...) — use doReturn with explicit String[] cast
    doReturn(bodySpec).when(bodySpec).header(anyString(), (String[]) any());
    when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
    when(bodySpec.retrieve()).thenReturn(responseSpec);

    service =
        new ConsoleSelfServiceJobService(
            restClientBuilder, orchestratorClientProperties, tenantGuard, environment);
  }

  @Test
  void shouldSubmitRerunApproval() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(responseSpec.body(Map.class)).thenReturn(Map.of("approvalNo", "APR-001"));

    RerunParam param = new RerunParam("tenant-a", "JOB-01", "2026-04-10", "INST-001", "test rerun");
    String approvalNo = service.requestRerun(param, "operator-1", "idem-key-1");

    assertThat(approvalNo).isEqualTo("APR-001");
  }

  @Test
  void shouldSubmitCompensationApproval() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(responseSpec.body(Map.class)).thenReturn(Map.of("approvalNo", "APR-002"));

    CompensationParam param =
        new CompensationParam(
            "tenant-a", "JOB-02", "2026-04-10", "FULL", "INST-002", "test compensation");
    String approvalNo = service.requestCompensation(param, "operator-2", "idem-key-2");

    assertThat(approvalNo).isEqualTo("APR-002");
  }

  @Test
  void shouldThrowWhenApprovalResponseMissing() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(responseSpec.body(Map.class)).thenReturn(null);

    RerunParam param = new RerunParam("tenant-a", "JOB-01", "2026-04-10", "INST-001", "test rerun");

    assertThatThrownBy(() -> service.requestRerun(param, "operator-1", "idem-key-1"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("submit_failed");
  }
}
