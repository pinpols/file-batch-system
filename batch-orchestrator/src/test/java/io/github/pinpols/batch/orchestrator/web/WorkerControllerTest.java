package io.github.pinpols.batch.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.WorkerHeartbeatDto;
import io.github.pinpols.batch.common.dto.WorkerPipelineProgressDto;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("工作节点注册与心跳接口的控制器行为,覆盖限流,租户归一,进度保留与下线指令下发")
class WorkerControllerTest {

  @Mock
  private WorkerRegistryServerService workerRegistryService;

  @Mock
  private WorkerDrainGovernanceService workerDrainGovernanceService;

  @Mock
  private TenantActionRateLimiter tenantActionRateLimiter;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new WorkerController(
            workerRegistryService, workerDrainGovernanceService, tenantActionRateLimiter))
        .setControllerAdvice(OrchestratorApiExceptionHandler.forStandaloneTest())
        .build();
  }

  @Test
  @DisplayName("下线请求体中的超时秒数被正确绑定并透传给治理服务")
  void shouldBindDrainRequestTimeoutSeconds() throws Exception {
    when(workerDrainGovernanceService.startDrain("t1", "worker-1", 1))
        .thenReturn(new WorkerRegistryEntity(
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
        .perform(post("/internal/workers/worker-1/drain")
            .contentType(APPLICATION_JSON)
            .content("""
                    {
                      "tenantId": "t1",
                      "timeoutSeconds": 1
                    }
                    """))
        .andExpect(status().isOk());

    verify(workerDrainGovernanceService).startDrain("t1", "worker-1", 1);
  }

  @Test
  @DisplayName("预热请求路由到治理服务并返回成功状态")
  void shouldRouteWarmupToGovernanceService() throws Exception {
    when(workerDrainGovernanceService.warmup("t1", "worker-1"))
        .thenReturn(new WorkerRegistryEntity(
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
        .perform(post("/internal/workers/worker-1/warmup")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\"}"))
        .andExpect(status().isOk());

    verify(workerDrainGovernanceService).warmup("t1", "worker-1");
  }

  @Test
  @DisplayName("鉴权上下文租户与请求体租户不一致时拒绝治理请求并返回禁止访问")
  void shouldRejectApiKeyTenantMismatchOnWorkerGovernanceRequest() throws Exception {
    mockMvc
        .perform(post("/internal/workers/worker-1/drain")
            .requestAttr(InternalAuthFilter.ATTR_RESOLVED_TENANT_ID, "tenant-a")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"tenant-b\",\"timeoutSeconds\":1}"))
        .andExpect(status().isForbidden());
  }

  // SDK Phase 2 §2.3:心跳回包下发 platform directive

  @Test
  @DisplayName("在线工作节点心跳返回常规调度指令,包含期望并发上限且不下发下线动作")
  void shouldReturnNormalDirective_whenHeartbeatFromOnlineWorker() throws Exception {
    when(workerRegistryService.heartbeat(eq("worker-1"), any(WorkerHeartbeatDto.class)))
        .thenReturn(onlineWorker("ONLINE", 8));

    mockMvc
        .perform(post("/internal/workers/worker-1/heartbeat")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"status\":\"RUNNING\",\"currentLoad\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.platformStatus").value("NORMAL"))
        .andExpect(jsonPath("$.shouldDrain").value(false))
        .andExpect(jsonPath("$.desiredMaxConcurrent").value(8))
        .andExpect(jsonPath("$.pausedTaskTypes").isEmpty());
  }

  @Test
  @DisplayName("心跳携带的租户标识经鉴权上下文归一后,管道任务进度明细仍完整保留")
  void shouldPreserveTaskProgress_whenHeartbeatTenantIsNormalized() throws Exception {
    when(workerRegistryService.heartbeat(eq("worker-1"), any(WorkerHeartbeatDto.class)))
        .thenReturn(onlineWorker("ONLINE", 8));
    mockMvc
        .perform(post("/internal/workers/worker-1/heartbeat")
            .requestAttr(InternalAuthFilter.ATTR_RESOLVED_TENANT_ID, "t1")
            .contentType(APPLICATION_JSON)
            .content("""
                {
                  "tenantId": "t1", "workerCode": "worker-1", "status": "RUNNING",
                  "pipelineProgress": [
                    {"taskId": 10, "pipelineInstanceId": 20, "stageCode": "LOAD",
                     "rowsProcessed": 100, "totalRowsHint": 200}
                  ]
                }
                """))
        .andExpect(status().isOk());
    ArgumentCaptor<WorkerHeartbeatDto> captor = ArgumentCaptor.forClass(WorkerHeartbeatDto.class);
    verify(workerRegistryService).heartbeat(eq("worker-1"), captor.capture());
    assertThat(captor.getValue().tenantId()).isEqualTo("t1");
    assertThat(captor.getValue().pipelineProgress())
        .containsExactly(new WorkerPipelineProgressDto(10L, 20L, "LOAD", 100L, 200L));
  }

  @Test
  @DisplayName("处于下线中的工作节点心跳返回下线指令,并要求停止接收新任务")
  void shouldReturnDrainDirective_whenWorkerIsDraining() throws Exception {
    when(workerRegistryService.heartbeat(eq("worker-1"), any(WorkerHeartbeatDto.class)))
        .thenReturn(onlineWorker("DRAINING", 8));

    mockMvc
        .perform(post("/internal/workers/worker-1/heartbeat")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"status\":\"RUNNING\",\"currentLoad\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.platformStatus").value("DRAINING"))
        .andExpect(jsonPath("$.shouldDrain").value(true));
  }

  // 缺口①: per-tenant worker 注册限流 (opt-in)

  @Test
  @DisplayName("租户级限流放行时工作节点注册成功并完成落库")
  void shouldRegisterWorker_whenRateLimiterAllowsRequest() throws Exception {
    when(tenantActionRateLimiter.tryConsume("t1", RateLimitAction.WORKER_REGISTER))
        .thenReturn(true);
    when(workerRegistryService.register(any(WorkerHeartbeatDto.class)))
        .thenReturn(onlineWorker("ONLINE", 10));

    mockMvc
        .perform(post("/internal/workers/register")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"workerCode\":\"w1\"}"))
        .andExpect(status().isOk());

    verify(workerRegistryService).register(any(WorkerHeartbeatDto.class));
  }

  @Test
  @DisplayName("注册请求携带的稳定工作节点池编码在租户归一后保持不变,不回退为节点编码")
  void shouldPreserveWorkerPoolCode_whenRegisterTenantIsNormalized() throws Exception {
    when(tenantActionRateLimiter.tryConsume("t1", RateLimitAction.WORKER_REGISTER))
        .thenReturn(true);
    when(workerRegistryService.register(any(WorkerHeartbeatDto.class)))
        .thenReturn(onlineWorker("ONLINE", 10));

    mockMvc
        .perform(
            post("/internal/workers/register").contentType(APPLICATION_JSON).content("""
                    {
                      "tenantId": "t1",
                      "workerCode": "export-heavy-pod-01",
                      "workerGroup": "EXPORT",
                      "workerPoolCode": "export-heavy"
                    }
                    """))
        .andExpect(status().isOk());

    ArgumentCaptor<WorkerHeartbeatDto> captor = ArgumentCaptor.forClass(WorkerHeartbeatDto.class);
    verify(workerRegistryService).register(captor.capture());
    assertThat(captor.getValue().workerCode()).isEqualTo("export-heavy-pod-01");
    assertThat(captor.getValue().workerPoolCode()).isEqualTo("export-heavy");
  }

  @Test
  @DisplayName("租户级限流拒绝时注册返回限流状态码,且不触发落库")
  void shouldRejectRegistration_whenRateLimiterDeniesRequest() throws Exception {
    when(tenantActionRateLimiter.tryConsume("t1", RateLimitAction.WORKER_REGISTER))
        .thenReturn(false);

    mockMvc
        .perform(post("/internal/workers/register")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"workerCode\":\"w1\"}"))
        .andExpect(status().isTooManyRequests());

    verify(workerRegistryService, never()).register(any(WorkerHeartbeatDto.class));
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
