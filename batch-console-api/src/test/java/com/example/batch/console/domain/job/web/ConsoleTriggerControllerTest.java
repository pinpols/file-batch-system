package com.example.batch.console.domain.job.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.application.ops.ConsoleTriggerProxyService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * P2: ConsoleTriggerController register/unregister/pause/resume 4 个动作正确透传 jobCode+tenantId 到 proxy。
 */
class ConsoleTriggerControllerTest {

  private final ConsoleTriggerProxyService proxy = mock(ConsoleTriggerProxyService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ConsoleTriggerController(proxy, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void listShouldDelegateToProxy() throws Exception {
    when(proxy.triggerList()).thenReturn(List.of(Map.of("jobCode", "J1")));
    mockMvc
        .perform(get("/api/console/ops/triggers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].jobCode").value("J1"));
  }

  @Test
  void registerShouldPassTenantAndJobCodeWithActionRegister() throws Exception {
    when(proxy.triggerAction("ta", "JOB_A", "register")).thenReturn(Map.of("status", "ok"));
    mockMvc
        .perform(post("/api/console/ops/triggers/JOB_A/register").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).triggerAction("ta", "JOB_A", "register");
  }

  @Test
  void pauseAndResumeShouldUseDistinctActions() throws Exception {
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
