package io.github.pinpols.batch.console.domain.job.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.domain.job.application.contract.request.DryRunPlanRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleDryRunPlanResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * P2: ConsoleDryRunPlanController 关键守护:类型化请求委托给应用端口，响应 envelope 不重复包装。
 */
class ConsoleDryRunPlanControllerTest {

  private final ConsoleOrchestratorPort orchestratorProxy = mock(ConsoleOrchestratorPort.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    when(orchestratorProxy.dryRunPlan(any(DryRunPlanRequest.class)))
        .thenReturn(
            CommonResponse.success(new ConsoleDryRunPlanResponse("L1", true, List.of(), Map.of())));

    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleDryRunPlanController(orchestratorProxy, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  @Test
  void planShouldDeserializeTypedBodyAndDelegateToApplicationPort() throws Exception {
    mockMvc
        .perform(post("/api/console/ops/dry-run/plan")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"tb\",\"jobCode\":\"job-a\",\"level\":\"L1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success").value(true));

    ArgumentCaptor<DryRunPlanRequest> requestCaptor =
        ArgumentCaptor.forClass(DryRunPlanRequest.class);
    verify(orchestratorProxy).dryRunPlan(requestCaptor.capture());
    DryRunPlanRequest forwarded = requestCaptor.getValue();
    assertThat(forwarded.getTenantId()).isEqualTo("tb");
    assertThat(forwarded.getJobCode()).isEqualTo("job-a");
    assertThat(forwarded.getLevel()).isEqualTo("L1");
  }

  @Test
  void planShouldPropagateApplicationFailure() throws Exception {
    when(orchestratorProxy.dryRunPlan(any(DryRunPlanRequest.class)))
        .thenThrow(BizException.of(ResultCode.FORBIDDEN, "error.tenant.mismatch"));
    mockMvc
        .perform(post("/api/console/ops/dry-run/plan")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"tb\",\"jobCode\":\"job-a\"}"))
        .andExpect(status().isForbidden());
    verify(orchestratorProxy).dryRunPlan(any(DryRunPlanRequest.class));
  }

  @Test
  void planShouldUnwrapOrchestratorEnvelopeAndNotDoubleNest() throws Exception {
    // J1 bugfix 守护:orchestrator 返 {success:true, data:{findings:[]}}, console 应该返
    // {success:true, data:{findings:[]}} —— 而不是嵌套 data.data。ADR-026 e2e
    // integration-adr-features:18 之前因为双重包装一直断言 success=false。
    when(orchestratorProxy.dryRunPlan(any(DryRunPlanRequest.class)))
        .thenReturn(CommonResponse.success(
            new ConsoleDryRunPlanResponse("L1", true, List.of(), Map.of("scheduledJobs", 3))));

    mockMvc
        .perform(post("/api/console/ops/dry-run/plan")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\",\"jobCode\":\"job-a\",\"level\":\"L1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.level").value("L1"))
        .andExpect(jsonPath("$.data.summary.scheduledJobs").value(3))
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
    when(orchestratorProxy.dryRunPlan(any(DryRunPlanRequest.class)))
        .thenReturn(CommonResponse.failure(ResultCode.BUSINESS_ERROR, "dry-run rejected"));

    mockMvc
        .perform(post("/api/console/ops/dry-run/plan")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\",\"jobCode\":\"job-a\",\"level\":\"L1\"}"))
        .andExpect(status().is5xxServerError());
  }

  @Test
  void planShouldHandleNullTenantInBody() throws Exception {
    mockMvc
        .perform(post("/api/console/ops/dry-run/plan")
            .contentType(APPLICATION_JSON)
            .content("{\"jobCode\":\"job-a\"}"))
        .andExpect(status().isOk());
    verify(orchestratorProxy).dryRunPlan(any(DryRunPlanRequest.class));
  }

  @Test
  void planShouldRejectMissingJobCodeWith400() throws Exception {
    // 类型化后的 bean validation 守护:jobCode @NotBlank,缺失直接 400,不触达 orchestrator。
    mockMvc
        .perform(post("/api/console/ops/dry-run/plan")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\"}"))
        .andExpect(status().isBadRequest());
    verify(orchestratorProxy, never()).dryRunPlan(any(DryRunPlanRequest.class));
  }
}
