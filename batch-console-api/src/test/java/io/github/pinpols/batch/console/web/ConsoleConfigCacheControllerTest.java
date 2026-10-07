package io.github.pinpols.batch.console.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** P2: ConsoleConfigCacheController 6 个 evict 动作正确按 (tenantId, code) 委托到 invalidation service。 */
@DisplayName("配置缓存失效控制器: 各类缓存清理动作按租户与编码委托到失效服务")
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
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleConfigCacheController(service, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  @Test
  @DisplayName("清理单个作业定义缓存时, 按租户与作业编码委托并返回清理键")
  void shouldCallService_whenEvictingJobDefinition() throws Exception {
    mockMvc
        .perform(post("/api/console/ops/cache/evict-job-definition")
            .param("tenantId", "ta")
            .param("jobCode", "JOB_A"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.evicted").value("job-definition:ta:JOB_A"));
    verify(service).evictJobDefinition("ta", "JOB_A");
  }

  @Test
  @DisplayName("清理租户下全部作业定义缓存时, 只按租户委托")
  void shouldCallService_whenEvictingAllJobDefinitions() throws Exception {
    mockMvc
        .perform(post("/api/console/ops/cache/evict-all-job-definitions").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(service).evictAllJobDefinitions("ta");
  }

  @Test
  @DisplayName("清理工作流定义缓存时, 租户与工作流编码传给服务")
  void shouldPassCode_whenEvictingWorkflowDefinition() throws Exception {
    mockMvc
        .perform(post("/api/console/ops/cache/evict-workflow-definition")
            .param("tenantId", "ta")
            .param("workflowCode", "WF_A"))
        .andExpect(status().isOk());
    verify(service).evictWorkflowDefinition("ta", "WF_A");
  }

  @Test
  @DisplayName("清理业务日历与批次窗口缓存时, 两者各自编码分别传给服务")
  void shouldDelegate_whenEvictingCalendarAndBatchWindow() throws Exception {
    mockMvc
        .perform(post("/api/console/ops/cache/evict-business-calendar")
            .param("tenantId", "ta")
            .param("calendarCode", "C_A"))
        .andExpect(status().isOk());
    mockMvc
        .perform(post("/api/console/ops/cache/evict-batch-window")
            .param("tenantId", "ta")
            .param("windowCode", "W_A"))
        .andExpect(status().isOk());
    verify(service).evictBusinessCalendar("ta", "C_A");
    verify(service).evictBatchWindow("ta", "W_A");
  }

  @Test
  @DisplayName("清理配额策略缓存时, 只按租户委托")
  void shouldDelegate_whenEvictingQuotaPolicies() throws Exception {
    mockMvc
        .perform(post("/api/console/ops/cache/evict-quota-policies").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(service).evictQuotaPolicies("ta");
  }
}
