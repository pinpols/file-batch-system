package com.example.batch.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.infrastructure.ops.OrchestratorInternalRestClient;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

/**
 * P2: ConsoleDryRunPlanController 关键守护:tenantGuard 强制覆盖 body.tenantId、转发到 orchestrator
 * /internal/orchestrator/dry-run/plan。
 */
class ConsoleDryRunPlanControllerTest {

  private final OrchestratorInternalRestClient orchestratorInternalRestClient =
      mock(OrchestratorInternalRestClient.class);
  private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);

  private RestClient restClient;
  private RestClient.RequestBodyUriSpec bodyUriSpec;
  private RestClient.RequestBodySpec bodySpec;
  private RestClient.ResponseSpec responseSpec;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    restClient = mock(RestClient.class);
    bodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
    bodySpec = mock(RestClient.RequestBodySpec.class);
    responseSpec = mock(RestClient.ResponseSpec.class);
    when(orchestratorInternalRestClient.build()).thenReturn(restClient);
    when(restClient.post()).thenReturn(bodyUriSpec);
    when(bodyUriSpec.uri(anyString())).thenReturn(bodySpec);
    when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
    when(bodySpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(ArgumentMatchers.<ParameterizedTypeReference<Object>>any()))
        .thenReturn(Map.of("level", "L1", "ok", true));

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleDryRunPlanController(
                    orchestratorInternalRestClient, tenantGuard, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void planShouldOverwriteBodyTenantWithResolvedTenant() throws Exception {
    // body 提交 tb,tenantGuard 解析为 ta(JWT 强制覆盖) → 上游收到 sanitized.tenantId=ta
    when(tenantGuard.resolveTenant("tb")).thenReturn("ta");

    mockMvc
        .perform(
            post("/api/console/ops/dry-run/plan")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"tb\",\"level\":\"L1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.ok").value(true));

    ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
    verify(bodySpec).body(bodyCaptor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> forwarded = (Map<String, Object>) bodyCaptor.getValue();
    assertThat(forwarded).containsEntry("tenantId", "ta");
    verify(bodyUriSpec).uri("/internal/orchestrator/dry-run/plan");
  }

  @Test
  void planShouldRejectWhenTenantGuardThrows() throws Exception {
    doThrow(BizException.of(ResultCode.FORBIDDEN, "error.tenant.mismatch"))
        .when(tenantGuard)
        .resolveTenant("tb");
    mockMvc
        .perform(
            post("/api/console/ops/dry-run/plan")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"tb\"}"))
        .andExpect(status().isForbidden());
    verify(orchestratorInternalRestClient, never()).build();
  }

  @Test
  void planShouldHandleNullTenantInBody() throws Exception {
    when(tenantGuard.resolveTenant(null)).thenReturn("ta");
    mockMvc
        .perform(post("/api/console/ops/dry-run/plan").contentType(APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    verify(tenantGuard).resolveTenant(null);
  }
}
