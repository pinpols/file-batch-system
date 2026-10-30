package io.github.pinpols.batch.console.domain.job.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.view.ConsoleSchedulerSnapshotResponse;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@DisplayName("调度器快照控制器: 实时快照与历史快照查询的转发")
@ExtendWith(MockitoExtension.class)
class ConsoleSchedulerSnapshotControllerTest {

  @Mock
  private ConsoleOrchestratorPort orchestratorProxyService;

  @Mock
  private ConsoleRequestMetadataResolver requestMetadataResolver;

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
            new ConsoleSchedulerSnapshotController(orchestratorProxyService, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("查询实时快照返回成功, 回包携带租户与快照内容")
  void shouldReturn200WhenGetLiveSnapshot() throws Exception {
    Instant windowStartedAt = Instant.parse("2026-10-21T00:00:00Z");
    Instant windowExpiresAt = Instant.parse("2026-10-22T00:00:00Z");
    ConsoleSchedulerSnapshotResponse.PolicySnapshot policy =
        new ConsoleSchedulerSnapshotResponse.PolicySnapshot(
            "settlement-policy",
            "settlement",
            2,
            3,
            1,
            4,
            "SLIDING",
            1,
            2,
            windowStartedAt,
            windowExpiresAt,
            8,
            4,
            6,
            7,
            4,
            14);
    when(orchestratorProxyService.schedulerSnapshot(anyString()))
        .thenReturn(new ConsoleSchedulerSnapshotResponse(
            BatchDateTimeSupport.utcNow(), "t1", List.of(policy), List.of(), List.of()));

    mockMvc
        .perform(get("/api/console/scheduler/snapshot").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.tenantId").value("t1"))
        .andExpect(jsonPath("$.data.policies[0].activeJobs").value(4))
        .andExpect(jsonPath("$.data.policies[0].activePartitions").value(6))
        .andExpect(jsonPath("$.data.policies[0].groupActiveJobs").value(7))
        .andExpect(jsonPath("$.data.policies[0].effectiveTenantJobCap").value(4))
        .andExpect(jsonPath("$.data.policies[0].effectiveTenantPartitionCap").value(14))
        .andExpect(
            jsonPath("$.data.policies[0].quotaResetWindowExpiresAt").value("2026-10-22T00:00:00Z"));
  }

  @Test
  @DisplayName("查询历史快照返回成功, 支持租户与条数参数")
  void shouldReturn200WhenGetSnapshotHistory() throws Exception {
    when(orchestratorProxyService.schedulerSnapshotHistory(anyString(), anyInt()))
        .thenReturn(List.of());

    mockMvc
        .perform(get("/api/console/scheduler/snapshot/history")
            .param("tenantId", "t1")
            .param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
  }
}
