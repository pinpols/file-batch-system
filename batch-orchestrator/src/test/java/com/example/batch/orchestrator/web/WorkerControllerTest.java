package com.example.batch.orchestrator.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.orchestrator.application.service.WorkerDrainGovernanceService;
import com.example.batch.orchestrator.controller.OrchestratorApiExceptionHandler;
import com.example.batch.orchestrator.controller.WorkerController;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.service.WorkerRegistryServerService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkerControllerTest {

  private final WorkerRegistryServerService workerRegistryService =
      mock(WorkerRegistryServerService.class);
  private final WorkerDrainGovernanceService workerDrainGovernanceService =
      mock(WorkerDrainGovernanceService.class);
  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(
              new WorkerController(workerRegistryService, workerDrainGovernanceService))
          .setControllerAdvice(new OrchestratorApiExceptionHandler())
          .build();

  @Test
  void shouldBindDrainRequestTimeoutSeconds() throws Exception {
    when(workerDrainGovernanceService.startDrain(eq("t1"), eq("worker-1"), eq(1)))
        .thenReturn(
            new WorkerRegistryRecord(
                1L,
                "t1",
                "worker-1",
                "import",
                null,
                null,
                "DRAINING",
                Instant.now(),
                0,
                Instant.now(),
                Instant.now()));

    mockMvc
        .perform(
            post("/internal/workers/worker-1/drain")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "tenantId": "t1",
                      "timeoutSeconds": 1
                    }
                    """))
        .andExpect(status().isOk());

    verify(workerDrainGovernanceService).startDrain("t1", "worker-1", 1);
  }
}
