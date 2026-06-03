package com.example.batch.orchestrator.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.orchestrator.application.service.governance.DeadLetterOrphanSourceException;
import com.example.batch.orchestrator.application.service.governance.RetryGovernanceService;
import com.example.batch.orchestrator.controller.DeadLetterController;
import com.example.batch.orchestrator.controller.OrchestratorApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DeadLetterControllerTest {

  private final RetryGovernanceService retryGovernanceService = mock(RetryGovernanceService.class);
  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new DeadLetterController(retryGovernanceService))
          .setControllerAdvice(OrchestratorApiExceptionHandler.forStandaloneTest())
          .build();

  @Test
  void shouldReplayDeadLetter() throws Exception {
    mockMvc
        .perform(
            post("/internal/dead-letters/42/replay")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\"}"))
        .andExpect(status().isOk());

    verify(retryGovernanceService).replayDeadLetter("t1", 42L);
  }

  @Test
  void shouldReturnConflictWhenReplayEncountersOrphanDeadLetterSource() throws Exception {
    doThrow(new DeadLetterOrphanSourceException("orphan"))
        .when(retryGovernanceService)
        .replayDeadLetter("t1", 99L);

    mockMvc
        .perform(
            post("/internal/dead-letters/99/replay")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\"}"))
        .andExpect(status().isConflict());
  }
}
