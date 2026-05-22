package com.example.batch.console.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.infrastructure.config.ConsoleConfigCacheInvalidationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** P2: ConsoleConfigCacheController 6 个 evict 动作正确按 (tenantId, code) 委托到 invalidation service。 */
class ConsoleConfigCacheControllerTest {

  private final ConsoleConfigCacheInvalidationService service =
      mock(ConsoleConfigCacheInvalidationService.class);
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
        MockMvcBuilders.standaloneSetup(new ConsoleConfigCacheController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void evictJobDefinitionShouldCallService() throws Exception {
    mockMvc
        .perform(
            post("/api/console/ops/cache/evict-job-definition")
                .param("tenantId", "ta")
                .param("jobCode", "JOB_A"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.evicted").value("job-definition:ta:JOB_A"));
    verify(service).evictJobDefinition("ta", "JOB_A");
  }

  @Test
  void evictAllJobDefinitionsShouldCallService() throws Exception {
    mockMvc
        .perform(post("/api/console/ops/cache/evict-all-job-definitions").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(service).evictAllJobDefinitions("ta");
  }

  @Test
  void evictWorkflowDefinitionShouldPassCode() throws Exception {
    mockMvc
        .perform(
            post("/api/console/ops/cache/evict-workflow-definition")
                .param("tenantId", "ta")
                .param("workflowCode", "WF_A"))
        .andExpect(status().isOk());
    verify(service).evictWorkflowDefinition("ta", "WF_A");
  }

  @Test
  void evictBusinessCalendarAndBatchWindowShouldDelegate() throws Exception {
    mockMvc
        .perform(
            post("/api/console/ops/cache/evict-business-calendar")
                .param("tenantId", "ta")
                .param("calendarCode", "C_A"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/console/ops/cache/evict-batch-window")
                .param("tenantId", "ta")
                .param("windowCode", "W_A"))
        .andExpect(status().isOk());
    verify(service).evictBusinessCalendar("ta", "C_A");
    verify(service).evictBatchWindow("ta", "W_A");
  }

  @Test
  void evictQuotaPoliciesShouldDelegate() throws Exception {
    mockMvc
        .perform(post("/api/console/ops/cache/evict-quota-policies").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(service).evictQuotaPolicies("ta");
  }
}
