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
import io.github.pinpols.batch.console.application.contract.response.ops.LineageEvidenceResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("血缘证据控制器: 按结果版本与业务键查询, 并透传租户越权拒绝")
class ConsoleLineageEvidenceControllerTest {

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
    LineageEvidenceResponse response = new LineageEvidenceResponse(
        Map.of("id", 7, "businessKey", "job:daily:2026-06-30"),
        null,
        List.of(),
        List.of(Map.of("id", 11, "fileName", "out.csv")),
        List.of(),
        new LineageEvidenceResponse.LineageCoverage(
            "BFS_HOT_TABLES", 7L, Map.of(), true, 11L, true, 0, 1, 1, List.of()));
    when(orchestratorProxy.lineageByResultVersion(7L, "ta"))
        .thenReturn(CommonResponse.success(response));
    when(orchestratorProxy.lineageByEffective("ta", "job:daily:2026-06-30"))
        .thenReturn(CommonResponse.success(response));
    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleLineageEvidenceController(orchestratorProxy, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  @Test
  @DisplayName("按结果版本查询血缘时, 响应返回版本明细、文件记录与覆盖统计")
  void shouldDelegateAndForwardEvidence_whenQueryingByResultVersion() throws Exception {
    mockMvc
        .perform(get("/api/console/lineage/result-versions/7").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.resultVersion.id").value(7))
        .andExpect(jsonPath("$.data.fileRecords[0].id").value(11))
        .andExpect(jsonPath("$.data.coverage.dispatchRecordCount").value(1));
    verify(orchestratorProxy).lineageByResultVersion(7L, "ta");
  }

  @Test
  @DisplayName("按业务键查询生效血缘时, 业务键原样传给编排端口")
  void shouldDelegateBusinessKey_whenQueryingEffectiveLineage() throws Exception {
    mockMvc
        .perform(get("/api/console/lineage/effective")
            .param("tenantId", "ta")
            .param("businessKey", "job:daily:2026-06-30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultVersion.businessKey").value("job:daily:2026-06-30"));
    verify(orchestratorProxy).lineageByEffective("ta", "job:daily:2026-06-30");
  }

  @Test
  @DisplayName("租户不匹配被应用端口拒绝时, 接口返回禁止访问")
  void shouldPropagateTenantRejectionFromApplicationPort() throws Exception {
    doThrow(BizException.of(ResultCode.FORBIDDEN, "error.tenant.mismatch"))
        .when(orchestratorProxy)
        .lineageByResultVersion(7L, "tb");
    mockMvc
        .perform(get("/api/console/lineage/result-versions/7").param("tenantId", "tb"))
        .andExpect(status().isForbidden());
    verify(orchestratorProxy).lineageByResultVersion(7L, "tb");
  }
}
