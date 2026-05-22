package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.infrastructure.ops.OrchestratorInternalRestClient;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

/**
 * P2: ConsoleResultVersionController 5 个端点(list/effective/detail/promote/reject)守 tenantGuard 解析 +
 * URL/HTTP method 正确路由到 orchestrator internal API。
 */
class ConsoleResultVersionControllerTest {

  private final OrchestratorInternalRestClient orchestratorInternalRestClient =
      mock(OrchestratorInternalRestClient.class);
  private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);

  private RestClient restClient;
  private RestClient.RequestHeadersUriSpec<?> getUriSpec;
  private RestClient.RequestHeadersSpec<?> getSpec;
  private RestClient.RequestBodyUriSpec postUriSpec;
  private RestClient.RequestBodySpec postSpec;
  private RestClient.ResponseSpec responseSpec;
  private MockMvc mockMvc;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        new ConsoleApiExceptionHandler(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    restClient = mock(RestClient.class);
    getUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
    getSpec = mock(RestClient.RequestHeadersSpec.class);
    postUriSpec = mock(RestClient.RequestBodyUriSpec.class);
    postSpec = mock(RestClient.RequestBodySpec.class);
    responseSpec = mock(RestClient.ResponseSpec.class);

    when(orchestratorInternalRestClient.build()).thenReturn(restClient);
    when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) getUriSpec);
    when(restClient.post()).thenReturn(postUriSpec);
    when(getUriSpec.uri(anyString(), any(Object[].class)))
        .thenReturn((RestClient.RequestHeadersSpec) getSpec);
    when(postUriSpec.uri(anyString(), any(Object[].class))).thenReturn(postSpec);
    when(getSpec.retrieve()).thenReturn(responseSpec);
    when(postSpec.retrieve()).thenReturn(responseSpec);
    // list 期望 wrapped {data: [...]} 解包
    when(responseSpec.body(any(ParameterizedTypeReference.class)))
        .thenReturn(Map.of("data", List.of(Map.of("id", 1))));

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleResultVersionController(
                    orchestratorInternalRestClient, tenantGuard, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void listShouldPassResolvedTenantAndBusinessKey() throws Exception {
    when(tenantGuard.resolveTenant("ta")).thenReturn("ta");
    mockMvc
        .perform(
            get("/api/console/result-versions")
                .param("tenantId", "ta")
                .param("businessKey", "BK_A"))
        .andExpect(status().isOk());
    verify(tenantGuard).resolveTenant("ta");
    verify(getUriSpec)
        .uri(
            "/internal/orchestrator/result-versions?tenantId={tenantId}&businessKey={businessKey}&limit={limit}",
            "ta",
            "BK_A",
            50);
  }

  @Test
  void effectiveShouldRouteToEffectiveEndpoint() throws Exception {
    when(tenantGuard.resolveTenant("ta")).thenReturn("ta");
    mockMvc
        .perform(
            get("/api/console/result-versions/effective")
                .param("tenantId", "ta")
                .param("businessKey", "BK_A"))
        .andExpect(status().isOk());
    verify(getUriSpec)
        .uri(
            "/internal/orchestrator/result-versions/effective?tenantId={tenantId}&businessKey={businessKey}",
            "ta",
            "BK_A");
  }

  @Test
  void detailShouldRouteToIdEndpoint() throws Exception {
    when(tenantGuard.resolveTenant("ta")).thenReturn("ta");
    mockMvc
        .perform(get("/api/console/result-versions/7").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(getUriSpec)
        .uri("/internal/orchestrator/result-versions/{id}?tenantId={tenantId}", 7L, "ta");
  }

  @Test
  void promoteShouldPostToPromoteEndpoint() throws Exception {
    when(tenantGuard.resolveTenant("ta")).thenReturn("ta");
    mockMvc
        .perform(
            post("/api/console/result-versions/7/promote")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
                .param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(postUriSpec)
        .uri("/internal/orchestrator/result-versions/{id}/promote?tenantId={tenantId}", 7L, "ta");
  }

  @Test
  void rejectShouldPostToRejectEndpoint() throws Exception {
    when(tenantGuard.resolveTenant("ta")).thenReturn("ta");
    mockMvc
        .perform(
            post("/api/console/result-versions/7/reject")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
                .param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(postUriSpec)
        .uri("/internal/orchestrator/result-versions/{id}/reject?tenantId={tenantId}", 7L, "ta");
  }

  @Test
  void shouldBlockWhenTenantGuardRejects() throws Exception {
    doThrow(BizException.of(ResultCode.FORBIDDEN, "error.tenant.mismatch"))
        .when(tenantGuard)
        .resolveTenant("tb");
    mockMvc
        .perform(
            get("/api/console/result-versions")
                .param("tenantId", "tb")
                .param("businessKey", "BK_A"))
        .andExpect(status().isForbidden());
    verify(orchestratorInternalRestClient, org.mockito.Mockito.never()).build();
  }
}
