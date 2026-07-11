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
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
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
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ConsoleDashboardController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void jobStatsShouldUseDefaultDays7() throws Exception {
    // 键与真实 ConsoleDashboardQueryService.loadJobStats 输出一致：byStatus(动态维度键)/total/dailyTrend。
    when(service.jobStats("ta", 7))
        .thenReturn(
            Map.of(
                "byStatus", Map.of("SUCCESS", 8L, "FAILED", 2L),
                "total", 10L,
                "dailyTrend",
                    List.of(Map.of("day", "2026-05-20", "status", "SUCCESS", "count", 8L))));
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
        .thenReturn(
            Map.of(
                "byTriggerType", List.of(Map.of("type", "CRON", "count", 100L)),
                "dailyTrend", List.of(Map.of("day", "2026-05-20", "count", 100L))));
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
        .thenReturn(
            Map.of(
                "byStatus", List.of(Map.of("status", "ONLINE", "count", 3L)),
                "byWorkerGroup", List.of(),
                "activePartitionsByWorker",
                    List.of(Map.of("workerCode", "w-1", "activePartitions", 5L))));
    mockMvc
        .perform(get("/api/console/dashboard/worker-load").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.byStatus[0].status").value("ONLINE"))
        .andExpect(jsonPath("$.data.activePartitionsByWorker[0].workerCode").value("w-1"));
  }

  @Test
  void executionProgressShouldRequireJobCodeAndBizDate() throws Exception {
    when(service.executionProgress("ta", "JOB_A", "2026-05-20"))
        .thenReturn(
            List.of(
                Map.of(
                    "id",
                    9L,
                    "jobCode",
                    "JOB_A",
                    "instanceNo",
                    "INS-9",
                    "instanceStatus",
                    "RUNNING",
                    "expectedPartitions",
                    4,
                    "successPartitions",
                    2,
                    "failedPartitions",
                    0,
                    "completedPartitions",
                    2,
                    "progressPercent",
                    50L)));
    mockMvc
        .perform(
            get("/api/console/dashboard/execution-progress")
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
        .thenReturn(Map.of("tenantId", "ta", "jobDefinitions", 12L, "periodDays", 30));
    mockMvc
        .perform(get("/api/console/dashboard/tenant-usage").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.jobDefinitions").value(12))
        .andExpect(jsonPath("$.data.periodDays").value(30));
    verify(service).tenantUsage("ta", 30);
  }
}
