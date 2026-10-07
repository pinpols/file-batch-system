package io.github.pinpols.batch.console.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.contract.response.ops.CapacityProfileResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("容量画像控制器: 查询参数透传与租户越权拒绝")
class ConsoleCapacityProfileControllerTest {

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
    when(orchestratorProxy.capacityProfile(
            "ta", "2026-06-30T00:00:00Z", "2026-06-30T01:00:00Z", "JOB", 10))
        .thenReturn(CommonResponse.success(new CapacityProfileResponse(
            null, "ta", null, "JOB", "BFS_HOT_TABLES", List.of(), null, null)));
    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleCapacityProfileController(orchestratorProxy, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  @Test
  @DisplayName("查询容量画像时, 分组、时间范围与条数上限全部透传到编排端口")
  void shouldDelegateAllQueryParameters_whenQueryingCapacity() throws Exception {
    mockMvc
        .perform(get("/api/console/capacity-profile")
            .param("tenantId", "ta")
            .param("groupBy", "JOB")
            .param("from", "2026-06-30T00:00:00Z")
            .param("to", "2026-06-30T01:00:00Z")
            .param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.scope").value("BFS_HOT_TABLES"))
        .andExpect(jsonPath("$.data.groupBy").value("JOB"));
    verify(orchestratorProxy)
        .capacityProfile("ta", "2026-06-30T00:00:00Z", "2026-06-30T01:00:00Z", "JOB", 10);
  }

  @Test
  @DisplayName("租户不匹配被应用端口拒绝时, 接口返回禁止访问")
  void shouldPropagateTenantRejectionFromApplicationPort() throws Exception {
    doThrow(BizException.of(ResultCode.FORBIDDEN, "error.tenant.mismatch"))
        .when(orchestratorProxy)
        .capacityProfile("tb", null, null, "TENANT", 50);
    mockMvc
        .perform(get("/api/console/capacity-profile").param("tenantId", "tb"))
        .andExpect(status().isForbidden());
    verify(orchestratorProxy).capacityProfile("tb", null, null, "TENANT", 50);
  }
}
