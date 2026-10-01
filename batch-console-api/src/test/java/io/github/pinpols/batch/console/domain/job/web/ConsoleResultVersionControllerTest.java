package io.github.pinpols.batch.console.domain.job.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** P2: result_version 控制器只负责 HTTP 参数绑定、鉴权入口和应用端口委托。 */
class ConsoleResultVersionControllerTest {

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
    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleResultVersionController(orchestratorProxy, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  @Test
  void listShouldDelegateTenantBusinessKeyAndLimit() throws Exception {
    when(orchestratorProxy.resultVersions("ta", "BK_A", 50))
        .thenReturn(CommonResponse.success(List.of()));
    mockMvc
        .perform(get("/api/console/result-versions")
            .param("tenantId", "ta")
            .param("businessKey", "BK_A"))
        .andExpect(status().isOk());
    verify(orchestratorProxy).resultVersions("ta", "BK_A", 50);
  }

  @Test
  void effectiveShouldDelegateBusinessKey() throws Exception {
    when(orchestratorProxy.effectiveResultVersion("ta", "BK_A"))
        .thenReturn(CommonResponse.success(null));
    mockMvc
        .perform(get("/api/console/result-versions/effective")
            .param("tenantId", "ta")
            .param("businessKey", "BK_A"))
        .andExpect(status().isOk());
    verify(orchestratorProxy).effectiveResultVersion("ta", "BK_A");
  }

  @Test
  void detailShouldDelegateIdAndTenant() throws Exception {
    when(orchestratorProxy.resultVersion(7L, "ta")).thenReturn(CommonResponse.success(null));
    mockMvc
        .perform(get("/api/console/result-versions/7").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(orchestratorProxy).resultVersion(7L, "ta");
  }

  @Test
  void promoteShouldDelegateIdAndTenant() throws Exception {
    when(orchestratorProxy.promoteResultVersion(7L, "ta")).thenReturn(CommonResponse.success(null));
    mockMvc
        .perform(post("/api/console/result-versions/7/promote")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
            .param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(orchestratorProxy).promoteResultVersion(7L, "ta");
  }

  @Test
  void rejectShouldDelegateIdAndTenant() throws Exception {
    when(orchestratorProxy.rejectResultVersion(7L, "ta")).thenReturn(CommonResponse.success(null));
    mockMvc
        .perform(post("/api/console/result-versions/7/reject")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "k1")
            .param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(orchestratorProxy).rejectResultVersion(7L, "ta");
  }

  @Test
  void shouldPropagateApplicationTenantRejection() throws Exception {
    when(orchestratorProxy.resultVersions("tb", "BK_A", 50))
        .thenThrow(BizException.of(ResultCode.FORBIDDEN, "error.tenant.mismatch"));
    mockMvc
        .perform(get("/api/console/result-versions")
            .param("tenantId", "tb")
            .param("businessKey", "BK_A"))
        .andExpect(status().isForbidden());
    verify(orchestratorProxy).resultVersions("tb", "BK_A", 50);
  }
}
