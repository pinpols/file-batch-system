package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitProperties;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowLaunchWaitClaim;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("launch-wait 恢复: 一轮补满全部空闲槽位，运行结束后立即再扫")
class WorkflowLaunchWaitResumeTriggerTest {

  @Mock
  private TenantDirectoryMapper tenantDirectoryMapper;

  @Mock
  private WorkflowLaunchWaitService waitingService;

  @Mock
  private WorkflowRunLaunchService launchService;

  @Mock
  private OrchestratorGracefulShutdown gracefulShutdown;

  @Test
  @DisplayName("一轮认领到的每个空闲槽位都会启动")
  void shouldLaunchEveryClaimedFreeSlotInOneRound() {
    WorkflowLaunchWaitReconciler reconciler = reconciler();
    when(waitingService.claimDue(eq("ta"), eq(100), any()))
        .thenReturn(List.of(claim("req-a", 1L), claim("req-b", 2L)));
    when(waitingService.restoreRequest(eq("ta"), any()))
        .thenReturn(LaunchRequest.builder()
            .tenantId("ta")
            .requestId("req")
            .traceId("trace")
            .build());

    reconciler.reconcileTenant("ta");

    verify(launchService, times(2)).launch(any());
  }

  @Test
  @DisplayName("没有活动事务时，终态后立刻按租户再扫")
  void shouldResumeTenantImmediatelyWhenNoTransactionIsActive() {
    when(waitingService.claimDue(any(), anyInt(), any())).thenReturn(List.of());
    WorkflowLaunchWaitResumeTrigger trigger =
        new WorkflowLaunchWaitResumeTrigger(reconciler(), gracefulShutdown);

    trigger.resumeAfterCommit("ta");

    verify(waitingService).claimDue(eq("ta"), anyInt(), any());
  }

  @Test
  @DisplayName("排水期间终态不再触发恢复")
  void shouldNotResumeWhileDraining() {
    when(gracefulShutdown.isDraining()).thenReturn(true);
    WorkflowLaunchWaitResumeTrigger trigger =
        new WorkflowLaunchWaitResumeTrigger(reconciler(), gracefulShutdown);

    trigger.resumeAfterCommit("ta");

    verify(waitingService, never()).claimDue(any(), anyInt(), any());
  }

  private WorkflowLaunchWaitReconciler reconciler() {
    WorkflowLaunchWaitProperties properties = new WorkflowLaunchWaitProperties();
    properties.setBatchSize(100);
    return new WorkflowLaunchWaitReconciler(
        tenantDirectoryMapper, waitingService, launchService, properties, gracefulShutdown);
  }

  private static WorkflowLaunchWaitClaim claim(String requestId, long workflowId) {
    return new WorkflowLaunchWaitClaim(
        "ta", requestId, workflowId, LocalDate.of(2026, 1, 15), false, "{}");
  }
}
