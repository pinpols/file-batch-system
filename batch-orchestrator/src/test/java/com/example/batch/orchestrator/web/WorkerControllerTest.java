package com.example.batch.orchestrator.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.WorkerHeartbeatDto;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.application.service.governance.WorkerDrainGovernanceService;
import com.example.batch.orchestrator.config.InternalAuthFilter;
import com.example.batch.orchestrator.controller.OrchestratorApiExceptionHandler;
import com.example.batch.orchestrator.controller.WorkerController;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import com.example.batch.orchestrator.service.WorkerRegistryServerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class WorkerControllerTest {

  @Mock private WorkerRegistryServerService workerRegistryService;
  @Mock private WorkerDrainGovernanceService workerDrainGovernanceService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new WorkerController(workerRegistryService, workerDrainGovernanceService))
            .setControllerAdvice(OrchestratorApiExceptionHandler.forStandaloneTest())
            .build();
  }

  @Test
  void shouldBindDrainRequestTimeoutSeconds() throws Exception {
    when(workerDrainGovernanceService.startDrain(eq("t1"), eq("worker-1"), eq(1)))
        .thenReturn(
            new WorkerRegistryEntity(
                1L,
                "t1",
                "worker-1",
                "import",
                null,
                null,
                "DRAINING",
                BatchDateTimeSupport.utcNow(),
                0,
                10,
                BatchDateTimeSupport.utcNow(),
                BatchDateTimeSupport.utcNow()));

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

  @Test
  void shouldRouteWarmupToGovernanceService() throws Exception {
    when(workerDrainGovernanceService.warmup(eq("t1"), eq("worker-1")))
        .thenReturn(
            new WorkerRegistryEntity(
                1L,
                "t1",
                "worker-1",
                "import",
                null,
                null,
                "ONLINE",
                BatchDateTimeSupport.utcNow(),
                0,
                10,
                BatchDateTimeSupport.utcNow(),
                BatchDateTimeSupport.utcNow()));

    mockMvc
        .perform(
            post("/internal/workers/worker-1/warmup")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\"}"))
        .andExpect(status().isOk());

    verify(workerDrainGovernanceService).warmup("t1", "worker-1");
  }

  @Test
  void shouldRejectApiKeyTenantMismatchOnWorkerGovernanceRequest() throws Exception {
    mockMvc
        .perform(
            post("/internal/workers/worker-1/drain")
                .requestAttr(InternalAuthFilter.ATTR_RESOLVED_TENANT_ID, "tenant-a")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"tenant-b\",\"timeoutSeconds\":1}"))
        .andExpect(status().isForbidden());
  }

  // SDK Phase 2 §2.3:心跳回包下发 platform directive

  @Test
  void heartbeatReturnsNormalDirectiveForOnlineWorker() throws Exception {
    when(workerRegistryService.heartbeat(eq("worker-1"), any(WorkerHeartbeatDto.class)))
        .thenReturn(onlineWorker("ONLINE", 8));

    mockMvc
        .perform(
            post("/internal/workers/worker-1/heartbeat")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"status\":\"RUNNING\",\"currentLoad\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.platformStatus").value("NORMAL"))
        .andExpect(jsonPath("$.shouldDrain").value(false))
        .andExpect(jsonPath("$.desiredMaxConcurrent").value(8))
        .andExpect(jsonPath("$.pausedTaskTypes").isEmpty());
  }

  @Test
  void heartbeatReturnsDrainDirectiveForDrainingWorker() throws Exception {
    when(workerRegistryService.heartbeat(eq("worker-1"), any(WorkerHeartbeatDto.class)))
        .thenReturn(onlineWorker("DRAINING", 8));

    mockMvc
        .perform(
            post("/internal/workers/worker-1/heartbeat")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"status\":\"RUNNING\",\"currentLoad\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.platformStatus").value("DRAINING"))
        .andExpect(jsonPath("$.shouldDrain").value(true));
  }

  private WorkerRegistryEntity onlineWorker(String status, Integer maxConcurrent) {
    return new WorkerRegistryEntity(
        1L,
        "t1",
        "worker-1",
        "import",
        null,
        null,
        status,
        BatchDateTimeSupport.utcNow(),
        2,
        maxConcurrent,
        null,
        null);
  }
}
