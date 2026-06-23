package io.github.pinpols.batch.orchestrator.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.WorkerHeartbeatDto;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.ratelimit.RateLimitAction;
import io.github.pinpols.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import io.github.pinpols.batch.orchestrator.application.service.governance.WorkerDrainGovernanceService;
import io.github.pinpols.batch.orchestrator.config.InternalAuthFilter;
import io.github.pinpols.batch.orchestrator.controller.OrchestratorApiExceptionHandler;
import io.github.pinpols.batch.orchestrator.controller.WorkerController;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.service.WorkerRegistryServerService;
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
  @Mock private TenantActionRateLimiter tenantActionRateLimiter;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new WorkerController(
                    workerRegistryService, workerDrainGovernanceService, tenantActionRateLimiter))
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

  // 缺口①: per-tenant worker 注册限流 (opt-in)

  @Test
  void registerAllowedWhenRateLimiterPasses() throws Exception {
    when(tenantActionRateLimiter.tryConsume(eq("t1"), eq(RateLimitAction.WORKER_REGISTER)))
        .thenReturn(true);
    when(workerRegistryService.register(any(WorkerHeartbeatDto.class)))
        .thenReturn(onlineWorker("ONLINE", 10));

    mockMvc
        .perform(
            post("/internal/workers/register")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"workerCode\":\"w1\"}"))
        .andExpect(status().isOk());

    verify(workerRegistryService).register(any(WorkerHeartbeatDto.class));
  }

  @Test
  void registerRejectedWith429WhenRateLimited() throws Exception {
    when(tenantActionRateLimiter.tryConsume(eq("t1"), eq(RateLimitAction.WORKER_REGISTER)))
        .thenReturn(false);

    mockMvc
        .perform(
            post("/internal/workers/register")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"workerCode\":\"w1\"}"))
        .andExpect(status().isTooManyRequests());

    verify(workerRegistryService, org.mockito.Mockito.never())
        .register(any(WorkerHeartbeatDto.class));
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
