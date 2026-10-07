package io.github.pinpols.batch.console.domain.ops.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.ops.ConsoleClusterDiagnosticService;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleClusterDiagnosticResponse;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleInstanceDiagnosisResponse;
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
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@DisplayName("集群诊断接口:诊断与实例诊断两个端点透传服务层结果, 并以成功响应包裹")
class ConsoleClusterDiagnosticControllerTest {

  private final ConsoleClusterDiagnosticService diagnosticService =
      mock(ConsoleClusterDiagnosticService.class);
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

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleClusterDiagnosticController(diagnosticService, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("集群诊断:各分组明细按服务层结果回填, 响应码为成功")
  void shouldReturnDiagnostic() throws Exception {
    // 键与真实 ConsoleClusterDiagnosticService.loadDiagnose
    // 输出一致：shedLock/workers/outbox/terminalChildren。
    when(diagnosticService.diagnose("t1"))
        .thenReturn(ConsoleClusterDiagnosticResponse.from(Map.of(
            "shedLock", Map.of("totalLocks", 2, "activeLocks", 1L, "locks", List.of()),
            "workers", Map.of("onlineWorkers", 3L, "healthy", true, "workerGroups", List.of()),
            "outbox", Map.of("pendingEvents", 0L, "healthy", true, "deliveryStats", List.of()),
            "terminalChildren",
                Map.of("terminalInstancesWithActiveChildren", 0L, "healthy", true))));

    mockMvc
        .perform(get("/api/console/ops/cluster-diagnostic").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.shedLock.totalLocks").value(2))
        .andExpect(jsonPath("$.data.workers.onlineWorkers").value(3))
        .andExpect(jsonPath("$.data.outbox.healthy").value(true))
        .andExpect(jsonPath("$.data.terminalChildren.terminalInstancesWithActiveChildren")
            .value(0));
  }

  @Test
  @DisplayName("实例诊断:实例标识与健康标记按服务层结果回填")
  void shouldReturnInstanceDiagnosis() throws Exception {
    when(diagnosticService.instanceDiagnosis("t1", 7L))
        .thenReturn(
            ConsoleInstanceDiagnosisResponse.from(Map.of("healthy", false, "jobInstanceId", 7L)));

    mockMvc
        .perform(get("/api/console/ops/cluster-diagnostic/instances/7").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.healthy").value(false))
        .andExpect(jsonPath("$.data.jobInstanceId").value(7));
  }
}
