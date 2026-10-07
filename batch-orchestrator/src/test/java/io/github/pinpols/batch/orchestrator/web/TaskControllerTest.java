package io.github.pinpols.batch.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.orchestrator.application.ratelimit.RateLimitAction;
import io.github.pinpols.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskAssignmentService.TaskHeartbeatResult;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskReportRetryExecutor;
import io.github.pinpols.batch.orchestrator.config.BundleBatchClaimProperties;
import io.github.pinpols.batch.orchestrator.config.InternalAuthFilter;
import io.github.pinpols.batch.orchestrator.controller.OrchestratorApiExceptionHandler;
import io.github.pinpols.batch.orchestrator.controller.TaskController;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
@DisplayName("任务执行接口,验证认领与上报的限流拒绝,认领成功与冲突及任务不存在,租户边界,有效配置下发,续约与批量续约以及取消请求的响应")
class TaskControllerTest {

  @Mock
  private TaskExecutionService taskExecutionService;

  @Mock
  private TenantActionRateLimiter tenantActionRateLimiter;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    // 默认放行;按-租户热路径限流仅 claim/report 触发,renew/cancel 等不触发,故用 lenient 避免 strict 误报。
    lenient().when(tenantActionRateLimiter.tryConsume(any(), any())).thenReturn(true);
    TaskControllerApplicationService taskControllerApplicationService =
        new TaskControllerApplicationService(
            taskExecutionService,
            new ObjectMapper(),
            new BundleBatchClaimProperties(),
            new SimpleMeterRegistry(),
            new TaskReportRetryExecutor(taskExecutionService));
    mockMvc = MockMvcBuilders.standaloneSetup(
            new TaskController(taskControllerApplicationService, tenantActionRateLimiter))
        .setControllerAdvice(OrchestratorApiExceptionHandler.forStandaloneTest())
        .build();
  }

  @Test
  @DisplayName("认领动作被租户限流时返回请求过多,且不触达任务分配逻辑")
  void shouldReturn429WhenClaimRateLimited() throws Exception {
    when(tenantActionRateLimiter.tryConsume("t1", RateLimitAction.TASK_CLAIM)).thenReturn(false);

    mockMvc
        .perform(post("/internal/tasks/10/claim")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isTooManyRequests());

    verify(taskExecutionService, never()).assignWorker(any(), any(), any());
  }

  @Test
  @DisplayName("上报动作被租户限流时返回请求过多,且不触达结果落库逻辑")
  void shouldReturn429WhenReportRateLimited() throws Exception {
    when(tenantActionRateLimiter.tryConsume("t1", RateLimitAction.TASK_REPORT)).thenReturn(false);

    mockMvc
        .perform(post("/internal/tasks/5/report")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"success\":true,\"resultSummary\":\"ok\"}"))
        .andExpect(status().isTooManyRequests());

    verify(taskExecutionService, never()).applyTaskOutcome(any());
  }

  @Test
  @DisplayName("任务可被认领时返回成功状态")
  void shouldReturn200WhenClaimSucceeds() throws Exception {
    JobTaskEntity task = new JobTaskEntity();
    task.setTaskStatus(TaskStatus.RUNNING.code());
    task.setAssignedWorkerCode("w1");
    when(taskExecutionService.assignWorker("t1", 10L, "w1")).thenReturn(task);

    mockMvc
        .perform(post("/internal/tasks/10/claim")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("认领成功时返回有效配置,作业编码,执行模式,分片区间,重试与超时参数及高水位取值均与配置源一致")
  void shouldReturnEffectiveConfigBodyWhenClaimSucceeds() throws Exception {
    JobTaskEntity task = new JobTaskEntity();
    task.setTaskStatus(TaskStatus.RUNNING.code());
    task.setAssignedWorkerCode("w1");
    when(taskExecutionService.assignWorker("t1", 10L, "w1")).thenReturn(task);
    EffectiveTaskConfig config = new EffectiveTaskConfig(
        "t1",
        10L,
        100L,
        200L,
        "INST-1",
        "JOB-X",
        "IMPORT",
        1,
        "IMPORT",
        "HIGH",
        "biz",
        "idem",
        "{}",
        "trace-1",
        "INCREMENTAL",
        "update_time",
        "wm-1",
        "EXPONENTIAL",
        5,
        600,
        1,
        1,
        "JOB-X:2026-05-01:1",
        1,
        0,
        1,
        0L,
        100L,
        100L,
        null,
        null,
        null);
    // PERF(5.2b): claim 复用 assignWorker 返回的 task 实体拉 config
    when(taskExecutionService.loadEffectiveConfig(eq("t1"), same(task))).thenReturn(config);

    mockMvc
        .perform(post("/internal/tasks/10/claim")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
        .andExpect(jsonPath("$.jobCode").value("JOB-X"))
        .andExpect(jsonPath("$.executionMode").value("INCREMENTAL"))
        .andExpect(jsonPath("$.retryMaxCount").value(5))
        .andExpect(jsonPath("$.timeoutSeconds").value(600))
        .andExpect(jsonPath("$.partitionPlanVersion").value(1))
        .andExpect(jsonPath("$.shardIndex").value(0))
        .andExpect(jsonPath("$.shardTotal").value(1))
        .andExpect(jsonPath("$.rangeStartInclusive").value(0))
        .andExpect(jsonPath("$.rangeEndExclusive").value(100))
        .andExpect(jsonPath("$.expectedRows").value(100))
        .andExpect(jsonPath("$.highWaterMarkIn").value("wm-1"));
  }

  @Test
  @DisplayName("接口密钥归属租户与请求体声明租户不一致时认领被拒绝并返回禁止访问")
  void shouldRejectApiKeyTenantMismatchOnClaim() throws Exception {
    mockMvc
        .perform(post("/internal/tasks/10/claim")
            .requestAttr(InternalAuthFilter.ATTR_RESOLVED_TENANT_ID, "tenant-a")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"tenant-b\",\"workerId\":\"w1\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("请求体未声明租户时按接口密钥解析出的租户完成认领")
  void shouldUseResolvedTenantWhenApiKeyRequestOmitsBodyTenant() throws Exception {
    JobTaskEntity task = new JobTaskEntity();
    task.setTaskStatus(TaskStatus.RUNNING.code());
    task.setAssignedWorkerCode("w1");
    when(taskExecutionService.assignWorker("tenant-a", 10L, "w1")).thenReturn(task);

    mockMvc
        .perform(post("/internal/tasks/10/claim")
            .requestAttr(InternalAuthFilter.ATTR_RESOLVED_TENANT_ID, "tenant-a")
            .contentType(APPLICATION_JSON)
            .content("{\"workerId\":\"w1\"}"))
        .andExpect(status().isOk());

    verify(taskExecutionService).assignWorker("tenant-a", 10L, "w1");
  }

  @Test
  @DisplayName("认领的任务不存在时返回未找到")
  void shouldReturn404WhenTaskNotFoundOnClaim() throws Exception {
    when(taskExecutionService.assignWorker(any(), any(), any())).thenReturn(null);

    mockMvc
        .perform(post("/internal/tasks/99/claim")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("任务已被其它执行节点占用时认领返回冲突")
  void shouldReturn409WhenClaimConflict() throws Exception {
    JobTaskEntity task = new JobTaskEntity();
    task.setTaskStatus(TaskStatus.RUNNING.code());
    task.setAssignedWorkerCode("other");
    when(taskExecutionService.assignWorker("t1", 10L, "w1")).thenReturn(task);

    mockMvc
        .perform(post("/internal/tasks/10/claim")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("上报请求被受理时返回成功状态")
  void shouldReturn200WhenReportAccepted() throws Exception {
    mockMvc
        .perform(post("/internal/tasks/5/report").contentType(APPLICATION_JSON).content("""
                    {
                      "tenantId": "t1",
                      "success": true,
                      "resultSummary": "ok"
                    }
                    """))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("缺少结构化错误字段时,回退取用旧版错误码与错误信息并完整透传给下游")
  void shouldFallbackToLegacyCodeAndMessageWhenErrorFieldsMissing() throws Exception {
    mockMvc
        .perform(post("/internal/tasks/5/report").contentType(APPLICATION_JSON).content("""
                    {
                      "tenantId": "t1",
                      "success": false,
                      "code": "ERR_PARSE",
                      "message": "parse failed"
                    }
                    """))
        .andExpect(status().isOk());

    ArgumentCaptor<TaskOutcomeCommand> captor = ArgumentCaptor.forClass(TaskOutcomeCommand.class);
    verify(taskExecutionService).applyTaskOutcome(captor.capture());
    assertThat(captor.getValue().errorCode()).isEqualTo("ERR_PARSE");
    assertThat(captor.getValue().errorMessage()).isEqualTo("parse failed");
  }

  @Test
  @DisplayName("续约处理结果标记无需取消时,响应中取消标记为假")
  void shouldReturn200WithCancelFalseWhenRenewSucceeds() throws Exception {
    when(taskExecutionService.recordHeartbeat("t1", 7L, "w1", null, null))
        .thenReturn(new TaskHeartbeatResult(true, false));

    mockMvc
        .perform(post("/internal/tasks/7/renew")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cancelRequested").value(false));
  }

  @Test
  @DisplayName("续约处理结果标记需要取消时,响应中取消标记为真")
  void shouldReturnCancelRequestedTrueWhenPlatformCancelled() throws Exception {
    when(taskExecutionService.recordHeartbeat("t1", 7L, "w1", null, null))
        .thenReturn(new TaskHeartbeatResult(true, true));

    mockMvc
        .perform(post("/internal/tasks/7/renew")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cancelRequested").value(true));
  }

  @Test
  @DisplayName("续约携带进度明细时,明细内容被完整透传给心跳记录")
  void shouldPersistHeartbeatDetailsWhenRenewCarriesDetails() throws Exception {
    when(taskExecutionService.recordHeartbeat(eq("t1"), eq(7L), eq("w1"), any(), any()))
        .thenReturn(new TaskHeartbeatResult(true, false));

    mockMvc
        .perform(post("/internal/tasks/7/renew")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\",\"details\":{\"processed\":42}}"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
    verify(taskExecutionService)
        .recordHeartbeat(eq("t1"), eq(7L), eq("w1"), any(), details.capture());
    assertThat(details.getValue()).contains("\"processed\":42");
  }

  @Test
  @DisplayName("续约处理结果标记续约失败时返回冲突")
  void shouldReturn409WhenRenewRejected() throws Exception {
    when(taskExecutionService.recordHeartbeat("t1", 7L, "w1", null, null))
        .thenReturn(new TaskHeartbeatResult(false, false));

    mockMvc
        .perform(post("/internal/tasks/7/renew")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"workerId\":\"w1\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("取消登记成功时返回成功状态并记录取消请求")
  void shouldReturn200WhenCancelRequested() throws Exception {
    when(taskExecutionService.requestCancel("t1", 7L)).thenReturn(true);

    mockMvc
        .perform(post("/internal/tasks/7/cancel")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"t1\",\"reason\":\"manual\"}"))
        .andExpect(status().isOk());

    verify(taskExecutionService).requestCancel("t1", 7L);
  }

  @Test
  @DisplayName("批量续约逐项返回结果,任务编号与续约结果按入参顺序一一对应")
  void shouldReturn200WithPerTaskResultsForRenewBatch() throws Exception {
    // PERF(5.3): renewBatch 走 set-based renewLeaseBatch,一次下发、逐项结果与入参对齐
    when(taskExecutionService.renewLeaseBatch(any()))
        .thenReturn(java.util.List.of(
            new TaskHeartbeatResult(true, false), new TaskHeartbeatResult(false, false)));

    mockMvc
        .perform(post("/internal/tasks/leases/renew-batch")
            .contentType(APPLICATION_JSON)
            .content("""
                    {
                      "items": [
                        {"tenantId":"t1","taskId":7,"workerId":"w1"},
                        {"tenantId":"t1","taskId":8,"workerId":"w1"}
                      ]
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].taskId").value(7))
        .andExpect(jsonPath("$.results[0].renewed").value(true))
        .andExpect(jsonPath("$.results[1].taskId").value(8))
        .andExpect(jsonPath("$.results[1].renewed").value(false));

    verify(taskExecutionService).renewLeaseBatch(any());
  }
}
