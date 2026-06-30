package io.github.pinpols.batch.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class ConsoleAssetPartitionControllerTest {

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
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    restClient = mock(RestClient.class);
    getUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
    getSpec = mock(RestClient.RequestHeadersSpec.class);
    responseSpec = mock(RestClient.ResponseSpec.class);

    when(orchestratorInternalRestClient.build()).thenReturn(restClient);
    doReturn(getUriSpec).when(restClient).get();
    doReturn(getSpec).when(getUriSpec).uri(anyString(), any(Object[].class));
    when(getSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(any(ParameterizedTypeReference.class)))
        .thenReturn(
            Map.of(
                "ready",
                true,
                "reason",
                "READY",
                "assetCode",
                "asset-settlement-daily",
                "bizDate",
                "2026-06-30",
                "versionNo",
                3,
                "freshnessStatus",
                "EFFECTIVE",
                "payloadStorage",
                "OBJECT_STORE",
                "payloadRef",
                "s3://bucket/path/result.csv"));

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleAssetPartitionController(
                    orchestratorInternalRestClient, tenantGuard, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void readinessShouldPassResolvedTenantAndWrapRawReadinessPayload() throws Exception {
    when(tenantGuard.resolveTenant("ta")).thenReturn("ta");

    MvcResult result =
        mockMvc
            .perform(
                get("/api/console/asset-partitions/readiness")
                    .param("tenantId", "ta")
                    .param("jobCode", "settlement_daily")
                    .param("bizDate", "2026-06-30"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.ready").value(true))
            .andExpect(jsonPath("$.data.assetCode").value("asset-settlement-daily"))
            .andExpect(jsonPath("$.data.versionNo").value(3))
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).contains("\"traceId\":\"trace-1\"");
    verify(tenantGuard).resolveTenant("ta");
    verify(getUriSpec)
        .uri(
            "/internal/readiness/job?tenantId={tenantId}&jobCode={jobCode}&bizDate={bizDate}",
            "ta",
            "settlement_daily",
            LocalDate.parse("2026-06-30"));
  }

  @Test
  void readinessShouldBlockWhenTenantGuardRejects() throws Exception {
    doThrow(BizException.of(ResultCode.FORBIDDEN, "error.tenant.mismatch"))
        .when(tenantGuard)
        .resolveTenant("tb");

    mockMvc
        .perform(
            get("/api/console/asset-partitions/readiness")
                .param("tenantId", "tb")
                .param("jobCode", "settlement_daily")
                .param("bizDate", "2026-06-30"))
        .andExpect(status().isForbidden());

    verify(orchestratorInternalRestClient, org.mockito.Mockito.never()).build();
  }
}
