package io.github.pinpols.batch.console.web;

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
import io.github.pinpols.batch.console.application.contract.response.ops.AssetPartitionReadinessResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("资产分区就绪查询控制器: 原始结果信封包装与租户越权透传")
class ConsoleAssetPartitionControllerTest {

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
    when(orchestratorProxy.assetPartitionReadiness(
            "ta", "settlement_daily", LocalDate.parse("2026-06-30")))
        .thenReturn(new AssetPartitionReadinessResponse(
            true,
            "READY",
            "asset-settlement-daily",
            LocalDate.parse("2026-06-30"),
            "2026-06-30",
            "job:settlement_daily:2026-06-30",
            "EFFECTIVE",
            3,
            9L,
            "OBJECT_STORE",
            "s3://bucket/path/result.csv"));
    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleAssetPartitionController(orchestratorProxy, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  @Test
  @DisplayName("查询就绪状态时, 原始结果包装为统一信封并带出追踪标识")
  void shouldWrapRawPayload_whenQueryingReadiness() throws Exception {
    MvcResult result = mockMvc
        .perform(get("/api/console/asset-partitions/readiness")
            .param("tenantId", "ta")
            .param("jobCode", "settlement_daily")
            .param("bizDate", "2026-06-30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.ready").value(true))
        .andExpect(jsonPath("$.data.assetCode").value("asset-settlement-daily"))
        .andExpect(jsonPath("$.data.versionNo").value(3))
        .andReturn();
    org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
        .contains("\"traceId\":\"trace-1\"");
    verify(orchestratorProxy)
        .assetPartitionReadiness("ta", "settlement_daily", LocalDate.parse("2026-06-30"));
  }

  @Test
  @DisplayName("租户不匹配被应用端口拒绝时, 接口返回禁止访问")
  void shouldPropagateTenantRejection_whenTenantNotPermitted() throws Exception {
    doThrow(BizException.of(ResultCode.FORBIDDEN, "error.tenant.mismatch"))
        .when(orchestratorProxy)
        .assetPartitionReadiness("tb", "settlement_daily", LocalDate.parse("2026-06-30"));
    mockMvc
        .perform(get("/api/console/asset-partitions/readiness")
            .param("tenantId", "tb")
            .param("jobCode", "settlement_daily")
            .param("bizDate", "2026-06-30"))
        .andExpect(status().isForbidden());
    verify(orchestratorProxy)
        .assetPartitionReadiness("tb", "settlement_daily", LocalDate.parse("2026-06-30"));
  }
}
