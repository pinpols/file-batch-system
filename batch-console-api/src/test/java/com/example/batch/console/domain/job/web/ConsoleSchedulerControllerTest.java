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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** P2: ConsoleSchedulerController status / pause-all / resume-all 透传。 */
class ConsoleSchedulerControllerTest {

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
        MockMvcBuilders.standaloneSetup(new ConsoleSchedulerController(proxy, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void statusShouldReturnSchedulerState() throws Exception {
    when(proxy.schedulerStatus()).thenReturn(Map.of("state", "RUNNING"));
    mockMvc
        .perform(get("/api/console/scheduler/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("RUNNING"));
  }

  @Test
  void pauseAllAndResumeAllShouldDelegate() throws Exception {
    when(proxy.schedulerPauseAll()).thenReturn(Map.of("state", "PAUSED"));
    when(proxy.schedulerResumeAll()).thenReturn(Map.of("state", "RUNNING"));
    mockMvc.perform(post("/api/console/scheduler/pause-all")).andExpect(status().isOk());
    mockMvc.perform(post("/api/console/scheduler/resume-all")).andExpect(status().isOk());
    verify(proxy).schedulerPauseAll();
    verify(proxy).schedulerResumeAll();
  }
}
