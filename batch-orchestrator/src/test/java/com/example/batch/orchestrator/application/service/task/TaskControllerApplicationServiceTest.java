package com.example.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.orchestrator.controller.TaskController.TaskClaimRequest;
import com.example.batch.orchestrator.controller.request.TaskExecutionReportDto;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewBatchRequest;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewBatchResponse;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewItemPayload;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * 守护 worker → orchestrator HTTP 入口的边界处理:
 *
 * <ul>
 *   <li>claim: assignWorker 返 null → NOT_FOUND;状态/worker 不匹配 → CONFLICT
 *   <li>report: 失败时 errorCode/errorMessage 兼容新旧字段名;空缺时降级 "UNKNOWN"
 *   <li>renew: renewTaskLease 返 false → CONFLICT
 *   <li>renewBatch: 逐条独立结果,异常不中断,空入参返空
 * </ul>
 */
class TaskControllerApplicationServiceTest {

  @Mock private TaskExecutionService taskExecutionService;

  private TaskControllerApplicationService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new TaskControllerApplicationService(taskExecutionService);
  }

  // ===== claim =====

  @Test
  @DisplayName("claim: assignWorker 返 null → NOT_FOUND")
  void claimThrowsNotFoundWhenAssignReturnsNull() {
    when(taskExecutionService.assignWorker(anyString(), anyLong(), anyString())).thenReturn(null);

    assertThatThrownBy(() -> service.claim(100L, new TaskClaimRequest("ta", "w1", "inv-1")))
        .isInstanceOf(BizException.class);
    verify(taskExecutionService, never()).loadEffectiveConfig(anyString(), anyLong());
  }

  @Test
  @DisplayName("claim: 状态非 RUNNING → CONFLICT,不返 config")
  void claimThrowsConflictWhenNotRunning() {
    JobTaskEntity task = task(TaskStatus.READY.code(), "w1");
    when(taskExecutionService.assignWorker(eq("ta"), eq(100L), eq("w1"))).thenReturn(task);

    assertThatThrownBy(() -> service.claim(100L, new TaskClaimRequest("ta", "w1", "inv-1")))
        .isInstanceOf(BizException.class);
    verify(taskExecutionService, never()).loadEffectiveConfig(anyString(), anyLong());
  }

  @Test
  @DisplayName("claim: workerId 不匹配 → CONFLICT")
  void claimThrowsConflictWhenWorkerMismatch() {
    JobTaskEntity task = task(TaskStatus.RUNNING.code(), "w-other");
    when(taskExecutionService.assignWorker(anyString(), anyLong(), anyString())).thenReturn(task);

    assertThatThrownBy(() -> service.claim(100L, new TaskClaimRequest("ta", "w1", "inv-1")))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("claim: 状态 RUNNING + workerId 匹配 → 返回 effective config")
  void claimSucceedsAndReturnsConfig() {
    JobTaskEntity task = task(TaskStatus.RUNNING.code(), "w1");
    when(taskExecutionService.assignWorker(eq("ta"), eq(100L), eq("w1"))).thenReturn(task);
    when(taskExecutionService.loadEffectiveConfig(eq("ta"), eq(100L))).thenReturn(null);

    EffectiveTaskConfig result = service.claim(100L, new TaskClaimRequest("ta", "w1", "inv-1"));
    assertThat(result).isNull();
    verify(taskExecutionService).loadEffectiveConfig(eq("ta"), eq(100L));
  }

  // ===== report =====

  @Test
  @DisplayName("report: success=true → errorCode/message 强制 null,即使 DTO 里有")
  void reportSuccessClearsErrorFields() {
    TaskExecutionReportDto dto = new TaskExecutionReportDto();
    dto.setTenantId("ta");
    dto.setWorkerId("w1");
    dto.setSuccess(true);
    dto.setErrorCode("OLD");
    dto.setErrorMessage("msg");

    service.report(100L, dto);

    ArgumentCaptor<TaskOutcomeCommand> cap = ArgumentCaptor.forClass(TaskOutcomeCommand.class);
    verify(taskExecutionService).applyTaskOutcome(cap.capture());
    assertThat(cap.getValue().errorCode()).isNull();
    assertThat(cap.getValue().errorMessage()).isNull();
  }

  @Test
  @DisplayName("report: success=false + errorCode 优先于旧 code 字段")
  void reportFailureUsesNewFieldFirst() {
    TaskExecutionReportDto dto = new TaskExecutionReportDto();
    dto.setSuccess(false);
    dto.setErrorCode("NEW_ERR");
    dto.setCode("OLD_ERR");
    dto.setErrorMessage("new msg");
    dto.setMessage("old msg");

    service.report(100L, dto);

    ArgumentCaptor<TaskOutcomeCommand> cap = ArgumentCaptor.forClass(TaskOutcomeCommand.class);
    verify(taskExecutionService).applyTaskOutcome(cap.capture());
    assertThat(cap.getValue().errorCode()).isEqualTo("NEW_ERR");
    assertThat(cap.getValue().errorMessage()).isEqualTo("new msg");
  }

  @Test
  @DisplayName("report: success=false + 仅旧 code 字段 → 回退到 code")
  void reportFailureFallsBackToOldField() {
    TaskExecutionReportDto dto = new TaskExecutionReportDto();
    dto.setSuccess(false);
    dto.setCode("OLD_ERR");
    dto.setMessage("old msg");

    service.report(100L, dto);

    ArgumentCaptor<TaskOutcomeCommand> cap = ArgumentCaptor.forClass(TaskOutcomeCommand.class);
    verify(taskExecutionService).applyTaskOutcome(cap.capture());
    assertThat(cap.getValue().errorCode()).isEqualTo("OLD_ERR");
    assertThat(cap.getValue().errorMessage()).isEqualTo("old msg");
  }

  @Test
  @DisplayName("report: success=false 且都没填 → 降级 UNKNOWN")
  void reportFailureFallsBackToUnknown() {
    TaskExecutionReportDto dto = new TaskExecutionReportDto();
    dto.setSuccess(false);

    service.report(100L, dto);

    ArgumentCaptor<TaskOutcomeCommand> cap = ArgumentCaptor.forClass(TaskOutcomeCommand.class);
    verify(taskExecutionService).applyTaskOutcome(cap.capture());
    assertThat(cap.getValue().errorCode()).isEqualTo("UNKNOWN");
    assertThat(cap.getValue().errorMessage()).isEqualTo("UNKNOWN");
  }

  @Test
  @DisplayName("report: success=false → verifierFailures 强制 null(失败路径不再带 verifier 信息)")
  void reportFailureDropsVerifierFailures() {
    TaskExecutionReportDto dto = new TaskExecutionReportDto();
    dto.setSuccess(false);
    dto.setVerifierFailures(List.of());

    service.report(100L, dto);

    ArgumentCaptor<TaskOutcomeCommand> cap = ArgumentCaptor.forClass(TaskOutcomeCommand.class);
    verify(taskExecutionService).applyTaskOutcome(cap.capture());
    assertThat(cap.getValue().verifierFailures()).isNull();
  }

  // ===== renew =====

  @Test
  @DisplayName("renew: renewTaskLease 返 false → 抛 CONFLICT")
  void renewThrowsWhenLeaseRejected() {
    when(taskExecutionService.renewTaskLease(anyString(), anyLong(), anyString(), any()))
        .thenReturn(false);
    assertThatThrownBy(() -> service.renew(100L, new TaskClaimRequest("ta", "w1", "inv-1")))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("renew: 成功 → 不抛")
  void renewSucceedsSilently() {
    when(taskExecutionService.renewTaskLease(eq("ta"), eq(100L), eq("w1"), eq("inv-1")))
        .thenReturn(true);
    service.renew(100L, new TaskClaimRequest("ta", "w1", "inv-1"));
  }

  // ===== renewBatch =====

  @Test
  @DisplayName("renewBatch: null/空 入参 → 返空 list,不调底层")
  void renewBatch_empty_input() {
    assertThat(service.renewBatch(null).results()).isEmpty();
    assertThat(service.renewBatch(new TaskLeaseRenewBatchRequest(null)).results()).isEmpty();
    assertThat(service.renewBatch(new TaskLeaseRenewBatchRequest(List.of())).results()).isEmpty();
    verify(taskExecutionService, never())
        .renewTaskLease(anyString(), anyLong(), anyString(), any());
  }

  @Test
  @DisplayName("renewBatch: 多 item 独立结果,部分成功不影响其他")
  void renewBatch_independent_results() {
    when(taskExecutionService.renewTaskLease(anyString(), eq(1L), anyString(), any()))
        .thenReturn(true);
    when(taskExecutionService.renewTaskLease(anyString(), eq(2L), anyString(), any()))
        .thenReturn(false);
    when(taskExecutionService.renewTaskLease(anyString(), eq(3L), anyString(), any()))
        .thenReturn(true);

    TaskLeaseRenewBatchResponse resp =
        service.renewBatch(
            new TaskLeaseRenewBatchRequest(
                List.of(
                    new TaskLeaseRenewItemPayload("ta", 1L, "w1", "inv-1"),
                    new TaskLeaseRenewItemPayload("ta", 2L, "w1", "inv-2"),
                    new TaskLeaseRenewItemPayload("ta", 3L, "w1", "inv-3"))));

    assertThat(resp.results()).hasSize(3);
    assertThat(resp.results().get(0).renewed()).isTrue();
    assertThat(resp.results().get(1).renewed()).isFalse();
    assertThat(resp.results().get(2).renewed()).isTrue();
  }

  // ===== fixtures =====

  private JobTaskEntity task(String status, String workerCode) {
    JobTaskEntity t = new JobTaskEntity();
    t.setTaskStatus(status);
    t.setAssignedWorkerCode(workerCode);
    return t;
  }
}
