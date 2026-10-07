package io.github.pinpols.batch.console.domain.job.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.ops.ConsoleTriggerProxyService;
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

/**
 * P2: ConsoleTriggerController register/unregister/pause/resume 4 个动作正确透传 jobCode+tenantId 到 proxy。
 */
@DisplayName("触发器控制器: 列表查询与注册、暂停、恢复动作的转发")
class ConsoleTriggerControllerTest {

  private final ConsoleTriggerProxyService proxy = mock(ConsoleTriggerProxyService.class);
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
    mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleTriggerController(proxy, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  @Test
  @DisplayName("查询触发器列表, 回包保留下游返回的作业编码")
  void shouldDelegateTriggerList_whenListRequested() throws Exception {
    when(proxy.triggerList()).thenReturn(List.of(Map.of("jobCode", "J1")));
    mockMvc
        .perform(get("/api/console/ops/triggers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].jobCode").value("J1"));
  }

  @Test
  @DisplayName("注册动作下发的租户与作业编码与请求一致")
  void shouldPassTenantAndJobCode_whenRegisterRequested() throws Exception {
    when(proxy.triggerAction("ta", "JOB_A", "register")).thenReturn(Map.of("status", "ok"));
    mockMvc
        .perform(post("/api/console/ops/triggers/JOB_A/register").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).triggerAction("ta", "JOB_A", "register");
  }

  @Test
  @DisplayName("暂停与恢复分别按各自动作下发, 两次调用互不串用")
  void shouldUseDistinctActions_whenPauseAndResumeRequested() throws Exception {
    when(proxy.triggerAction("ta", "JOB_A", "pause")).thenReturn(Map.of("status", "paused"));
    when(proxy.triggerAction("ta", "JOB_A", "resume")).thenReturn(Map.of("status", "resumed"));
    mockMvc
        .perform(post("/api/console/ops/triggers/JOB_A/pause").param("tenantId", "ta"))
        .andExpect(status().isOk());
    mockMvc
        .perform(post("/api/console/ops/triggers/JOB_A/resume").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).triggerAction("ta", "JOB_A", "pause");
    verify(proxy).triggerAction("ta", "JOB_A", "resume");
  }
}
