package io.github.pinpols.batch.console.domain.job.web;

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

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.job.web.request.DryRunPlanRequest;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
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
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);
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
    // 模拟 orchestrator 返 CommonResponse<DryRunPlanResult> envelope —— 与生产端真实 wire 一致。
    // J1 bugfix 2026-06-04:之前 mock 直接返业务负载,绕开了"双层 envelope"路径,
    // 让 ConsoleDryRunPlanController 二次 success(resp) 包装 bug 在 unit-test 里看不见。
    when(responseSpec.body(ArgumentMatchers.<ParameterizedTypeReference<Object>>any()))
        .thenReturn(
            Map.of(
                "success",
                true,
                "code",
                "SUCCESS",
                "data",
                Map.of("level", "L1", "ok", true, "findings", List.of())));

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
                .content("{\"tenantId\":\"tb\",\"jobCode\":\"job-a\",\"level\":\"L1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.ok").value(true));

    ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
    verify(bodySpec).body(bodyCaptor.capture());
    DryRunPlanRequest forwarded = (DryRunPlanRequest) bodyCaptor.getValue();
    assertThat(forwarded.getTenantId()).isEqualTo("ta");
    assertThat(forwarded.getJobCode()).isEqualTo("job-a");
    assertThat(forwarded.getLevel()).isEqualTo("L1");
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
                .content("{\"tenantId\":\"tb\",\"jobCode\":\"job-a\"}"))
        .andExpect(status().isForbidden());
    verify(orchestratorInternalRestClient, never()).build();
  }

  @Test
  void planShouldUnwrapOrchestratorEnvelopeAndNotDoubleNest() throws Exception {
    // J1 bugfix 守护:orchestrator 返 {success:true, data:{findings:[]}}, console 应该返
    // {success:true, data:{findings:[]}} —— 而不是嵌套 data.data。ADR-026 e2e
    // integration-adr-features:18 之前因为双重包装一直断言 success=false。
    when(tenantGuard.resolveTenant("ta")).thenReturn("ta");
    when(responseSpec.body(ArgumentMatchers.<ParameterizedTypeReference<Object>>any()))
        .thenReturn(
            Map.of(
                "success",
                true,
                "code",
                "SUCCESS",
                "data",
                Map.of("findings", List.of(), "summary", "ok")));

    mockMvc
        .perform(
            post("/api/console/ops/dry-run/plan")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"ta\",\"jobCode\":\"job-a\",\"level\":\"L1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.summary").value("ok"))
        .andExpect(jsonPath("$.data.findings").isArray())
        // 关键负向断言:不能有嵌套 data.data / data.code 这条路径
        .andExpect(jsonPath("$.data.data").doesNotExist())
        .andExpect(jsonPath("$.data.code").doesNotExist());
  }

  @Test
  void planShouldPropagateOrchestratorFailureEnvelope() throws Exception {
    // orchestrator 显式返 success=false envelope 时(理论上 retrieve() 会因 HTTP 4xx 抛错先
    // 拦截,但 helper 自身的 success-flag 检查作为防御层回退),console 必须把失败信号传出去,
    // 不能把它当 success(payload) 让 FE 误以为成功。
    when(tenantGuard.resolveTenant("ta")).thenReturn("ta");
    when(responseSpec.body(ArgumentMatchers.<ParameterizedTypeReference<Object>>any()))
        .thenReturn(
            Map.of(
                "success", false,
                "code", "BUSINESS_ERROR",
                "message", "dry-run rejected"));

    mockMvc
        .perform(
            post("/api/console/ops/dry-run/plan")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"ta\",\"jobCode\":\"job-a\",\"level\":\"L1\"}"))
        .andExpect(status().is5xxServerError());
  }

  @Test
  void planShouldHandleNullTenantInBody() throws Exception {
    when(tenantGuard.resolveTenant(null)).thenReturn("ta");
    mockMvc
        .perform(
            post("/api/console/ops/dry-run/plan")
                .contentType(APPLICATION_JSON)
                .content("{\"jobCode\":\"job-a\"}"))
        .andExpect(status().isOk());
    verify(tenantGuard).resolveTenant(null);
  }

  @Test
  void planShouldRejectMissingJobCodeWith400() throws Exception {
    // 类型化后的 bean validation 守护:jobCode @NotBlank,缺失直接 400,不触达 orchestrator。
    mockMvc
        .perform(
            post("/api/console/ops/dry-run/plan")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"ta\"}"))
        .andExpect(status().isBadRequest());
    verify(orchestratorInternalRestClient, never()).build();
  }
}
