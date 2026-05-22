package com.example.batch.console.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.service.ConsoleDashboardQueryService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
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
        new ConsoleApiExceptionHandler(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ConsoleDashboardController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void jobStatsShouldUseDefaultDays7() throws Exception {
    when(service.jobStats("ta", 7)).thenReturn(Map.of("totalJobs", 10));
    mockMvc
        .perform(get("/api/console/dashboard/job-stats").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalJobs").value(10));
    verify(service).jobStats("ta", 7);
  }

  @Test
  void triggerStatsShouldUseCustomDays() throws Exception {
    when(service.triggerStats("ta", 30)).thenReturn(Map.of("count", 100));
    mockMvc
        .perform(
            get("/api/console/dashboard/trigger-stats").param("tenantId", "ta").param("days", "30"))
        .andExpect(status().isOk());
    verify(service).triggerStats("ta", 30);
  }

  @Test
  void workerLoadShouldDelegate() throws Exception {
    when(service.workerLoad("ta")).thenReturn(Map.of("workers", List.of()));
    mockMvc
        .perform(get("/api/console/dashboard/worker-load").param("tenantId", "ta"))
        .andExpect(status().isOk());
  }

  @Test
  void executionProgressShouldRequireJobCodeAndBizDate() throws Exception {
    when(service.executionProgress("ta", "JOB_A", "2026-05-20"))
        .thenReturn(List.of(Map.of("status", "RUNNING")));
    mockMvc
        .perform(
            get("/api/console/dashboard/execution-progress")
                .param("tenantId", "ta")
                .param("jobCode", "JOB_A")
                .param("bizDate", "2026-05-20"))
        .andExpect(status().isOk());
    verify(service).executionProgress("ta", "JOB_A", "2026-05-20");
  }

  @Test
  void tenantUsageShouldDefaultTo30Days() throws Exception {
    when(service.tenantUsage("ta", 30)).thenReturn(Map.of("usage", 1));
    mockMvc
        .perform(get("/api/console/dashboard/tenant-usage").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(service).tenantUsage("ta", 30);
  }
}
