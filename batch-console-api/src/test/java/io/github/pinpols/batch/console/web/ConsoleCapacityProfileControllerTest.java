package io.github.pinpols.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
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
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class ConsoleCapacityProfileControllerTest {

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
    doReturn(getSpec).when(getUriSpec).uri(any(Function.class));
    when(getSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(any(ParameterizedTypeReference.class)))
        .thenReturn(Map.of("data", Map.of("scope", "BFS_HOT_TABLES", "groupBy", "JOB")));

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleCapacityProfileController(
                    orchestratorInternalRestClient, tenantGuard, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void queryShouldResolveTenantAndProxyToOrchestrator() throws Exception {
    when(tenantGuard.resolveTenant("ta")).thenReturn("ta");

    mockMvc
        .perform(
            get("/api/console/capacity-profile")
                .param("tenantId", "ta")
                .param("groupBy", "JOB")
                .param("from", "2026-06-30T00:00:00Z")
                .param("to", "2026-06-30T01:00:00Z")
                .param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.scope").value("BFS_HOT_TABLES"))
        .andExpect(jsonPath("$.data.groupBy").value("JOB"));

    verify(tenantGuard).resolveTenant("ta");
    verify(getUriSpec).uri(any(Function.class));
  }

  @Test
  void shouldBlockWhenTenantGuardRejects() throws Exception {
    doThrow(BizException.of(ResultCode.FORBIDDEN, "error.tenant.mismatch"))
        .when(tenantGuard)
        .resolveTenant("tb");

    mockMvc
        .perform(get("/api/console/capacity-profile").param("tenantId", "tb"))
        .andExpect(status().isForbidden());

    verify(orchestratorInternalRestClient, org.mockito.Mockito.never()).build();
  }
}
