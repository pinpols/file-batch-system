package io.github.pinpols.batch.console.domain.observability.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.observability.service.ConsoleDashboardQueryService;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.ActivePartitionView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.DayCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.DayStatusCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.ExecutionProgressView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.JobStatsView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.StatusCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.TenantUsageView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.TriggerStatsView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.TypeCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.WorkerGroupStatusCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.WorkerLoadView;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** P2: ConsoleDashboardController 8 个端点透传 tenantId + days 参数到 query service。 */
class ConsoleDashboardControllerTest {

  private final ConsoleDashboardQueryService service = mock(ConsoleDashboardQueryService.class);
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
            new ConsoleDashboardController(service, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  @Test
  void jobStatsShouldUseDefaultDays7() throws Exception {
    when(service.jobStats("ta", 7))
        .thenReturn(new JobStatsView(
            Map.of("SUCCESS", 8L, "FAILED", 2L),
            10L,
            List.of(new DayStatusCountView(LocalDate.of(2026, 5, 20), "SUCCESS", 8L))));
    mockMvc
        .perform(get("/api/console/dashboard/job-stats").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(10))
        .andExpect(jsonPath("$.data.byStatus.SUCCESS").value(8))
        .andExpect(jsonPath("$.data.dailyTrend[0].status").value("SUCCESS"));
    verify(service).jobStats("ta", 7);
  }

  @Test
  void triggerStatsShouldUseCustomDays() throws Exception {
    when(service.triggerStats("ta", 30))
        .thenReturn(new TriggerStatsView(
            List.of(new TypeCountView("CRON", 100L)),
            List.of(new DayCountView(LocalDate.of(2026, 5, 20), 100L))));
    mockMvc
        .perform(
            get("/api/console/dashboard/trigger-stats").param("tenantId", "ta").param("days", "30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.byTriggerType[0].type").value("CRON"))
        .andExpect(jsonPath("$.data.byTriggerType[0].count").value(100));
    verify(service).triggerStats("ta", 30);
  }

  @Test
  void workerLoadShouldDelegate() throws Exception {
    when(service.workerLoad("ta"))
        .thenReturn(new WorkerLoadView(
            List.of(new StatusCountView("ONLINE", 3L)),
            List.of(new WorkerGroupStatusCountView("default", "ONLINE", 3L)),
            List.of(new ActivePartitionView("w-1", 5L))));
    mockMvc
        .perform(get("/api/console/dashboard/worker-load").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.byStatus[0].status").value("ONLINE"))
        .andExpect(jsonPath("$.data.activePartitionsByWorker[0].workerCode").value("w-1"));
  }

  @Test
  void executionProgressShouldRequireJobCodeAndBizDate() throws Exception {
    when(service.executionProgress("ta", "JOB_A", "2026-05-20"))
        .thenReturn(List.of(new ExecutionProgressView(
            9L, "JOB_A", "INS-9", "RUNNING", 4, 2, 0, null, null, 2, 50L)));
    mockMvc
        .perform(get("/api/console/dashboard/execution-progress")
            .param("tenantId", "ta")
            .param("jobCode", "JOB_A")
            .param("bizDate", "2026-05-20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].instanceStatus").value("RUNNING"))
        .andExpect(jsonPath("$.data[0].progressPercent").value(50));
    verify(service).executionProgress("ta", "JOB_A", "2026-05-20");
  }

  @Test
  void tenantUsageShouldDefaultTo30Days() throws Exception {
    when(service.tenantUsage("ta", 30))
        .thenReturn(new TenantUsageView("ta", 12L, 0L, 0L, 0L, 0L, 0L, 30));
    mockMvc
        .perform(get("/api/console/dashboard/tenant-usage").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.jobDefinitions").value(12))
        .andExpect(jsonPath("$.data.periodDays").value(30));
    verify(service).tenantUsage("ta", 30);
  }
}
