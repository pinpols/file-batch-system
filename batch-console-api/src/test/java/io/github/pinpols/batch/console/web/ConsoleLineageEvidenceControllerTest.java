package io.github.pinpols.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class ConsoleLineageEvidenceControllerTest {

  private final OrchestratorInternalRestClient orchestratorInternalRestClient =
      mock(OrchestratorInternalRestClient.class);
  private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);

  private RestClient restClient;
  private RestClient.RequestHeadersUriSpec<?> getUriSpec;
  private RestClient.RequestHeadersSpec<?> getSpec;
  private RestClient.ResponseSpec responseSpec;
  private MockMvc mockMvc;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);
    whenResponseMeta();

    restClient = mock(RestClient.class);
    getUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
    getSpec = mock(RestClient.RequestHeadersSpec.class);
    responseSpec = mock(RestClient.ResponseSpec.class);

    doReturn(restClient).when(orchestratorInternalRestClient).build();
    doReturn(getUriSpec).when(restClient).get();
    doReturn(getSpec).when(getUriSpec).uri(anyString(), any(Object[].class));
    doReturn(responseSpec).when(getSpec).retrieve();
    doReturn(
            Map.of(
                "code",
                "SUCCESS",
                "data",
                Map.of(
                    "resultVersion",
                    Map.of("id", 7, "businessKey", "job:daily:2026-06-30"),
                    "fileRecords",
                    List.of(Map.of("id", 11, "fileName", "out.csv")),
                    "coverage",
                    Map.of("knownGaps", List.of(), "dispatchRecordCount", 1))))
        .when(responseSpec)
        .body(any(ParameterizedTypeReference.class));

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleLineageEvidenceController(
                    orchestratorInternalRestClient, tenantGuard, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void byResultVersionShouldResolveTenantAndForwardEvidence() throws Exception {
    doReturn("ta").when(tenantGuard).resolveTenant("ta");

    mockMvc
        .perform(get("/api/console/lineage/result-versions/7").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.resultVersion.id").value(7))
        .andExpect(jsonPath("$.data.fileRecords[0].id").value(11))
        .andExpect(jsonPath("$.data.coverage.dispatchRecordCount").value(1));

    verify(getUriSpec)
        .uri("/internal/orchestrator/lineage/result-versions/{id}?tenantId={tenantId}", 7L, "ta");
  }

  @Test
  void effectiveShouldResolveTenantAndForwardBusinessKey() throws Exception {
    doReturn("ta").when(tenantGuard).resolveTenant("ta");

    mockMvc
        .perform(
            get("/api/console/lineage/effective")
                .param("tenantId", "ta")
                .param("businessKey", "job:daily:2026-06-30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultVersion.businessKey").value("job:daily:2026-06-30"));

    verify(getUriSpec)
        .uri(
            "/internal/orchestrator/lineage/effective?tenantId={tenantId}&businessKey={businessKey}",
            "ta",
            "job:daily:2026-06-30");
  }

  @Test
  void shouldBlockWhenTenantGuardRejects() throws Exception {
    doThrow(BizException.of(ResultCode.FORBIDDEN, "error.tenant.mismatch"))
        .when(tenantGuard)
        .resolveTenant("tb");

    mockMvc
        .perform(get("/api/console/lineage/result-versions/7").param("tenantId", "tb"))
        .andExpect(status().isForbidden());
  }

  private void whenResponseMeta() {
    doReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()))
        .when(requestMetadataResolver)
        .responseMeta();
  }
}
