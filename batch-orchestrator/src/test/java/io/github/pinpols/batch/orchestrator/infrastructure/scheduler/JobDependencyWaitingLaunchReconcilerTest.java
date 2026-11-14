package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.config.JobDependencyWaitProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDependencyWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;
import io.github.pinpols.batch.orchestrator.service.JobDependencyWaitingLaunchService;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("延迟启动重试: 派发失败时记录有界重试原因")
@ExtendWith(MockitoExtension.class)
class JobDependencyWaitingLaunchReconcilerTest {

  @Mock
  private TenantDirectoryMapper tenantDirectoryMapper;

  @Mock
  private JobDependencyWaitingLaunchService waitingLaunchService;

  @Mock
  private ObjectProvider<LaunchService> launchServiceProvider;

  @Mock
  private OrchestratorGracefulShutdown gracefulShutdown;

  @Mock
  private AlertEventService alertEventService;

  @Mock
  private ObjectProvider<WorkflowNodeDispatchService> workflowDispatchProvider;

  private JobDependencyWaitingLaunchReconciler reconciler;

  @BeforeEach
  void setUp() {
    reconciler = new JobDependencyWaitingLaunchReconciler(
        tenantDirectoryMapper,
        waitingLaunchService,
        launchServiceProvider,
        gracefulShutdown,
        alertEventService,
        mock(BatchDateTimeSupport.class),
        workflowDispatchProvider,
        new JobDependencyWaitProperties());
  }

  @Test
  @DisplayName("派发抛异常时记录 LAUNCH_RETRY 前缀且有长度上限的重试原因")
  void shouldRecordBoundedRetryReason_whenLaunchThrows() {
    TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
    triggerRequest.setRequestStatus(TriggerRequestStatus.PENDING.code());
    JobDependencyWaitingLaunchEntity entry = JobDependencyWaitingLaunchEntity.builder()
        .tenantId("t1")
        .requestId("req-1")
        .jobCode("job-1")
        .bizDate(LocalDate.of(2026, 11, 14))
        .dependencySnapshot("{}")
        .build();
    LaunchRequest launchRequest = LaunchRequest.builder().tenantId("t1").build();
    when(waitingLaunchService.selectOriginalRequest("t1", "req-1")).thenReturn(triggerRequest);
    when(waitingLaunchService.restoreWaitRequest(entry, triggerRequest))
        .thenReturn(Optional.of(launchRequest));
    when(waitingLaunchService.findNotReadyReason(
            eq("t1"), eq("job-1"), any(LocalDate.class), anyString()))
        .thenReturn(null);
    LaunchService launchService = mock(LaunchService.class);
    when(launchServiceProvider.getObject()).thenReturn(launchService);
    when(launchService.launch(any())).thenThrow(new IllegalStateException("boom"));

    reconciler.reconcileOne("t1", entry);

    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(waitingLaunchService).recordWaitCheck(eq("t1"), eq("req-1"), reason.capture());
    assertThat(reason.getValue()).startsWith("LAUNCH_RETRY:").hasSizeLessThanOrEqualTo(500);
  }
}
