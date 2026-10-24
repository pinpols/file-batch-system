package io.github.pinpols.batch.orchestrator.infrastructure.timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.application.service.task.WorkflowRunResumeProgressor;
import io.github.pinpols.batch.orchestrator.config.TimeoutEnforcerProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkflowRunStuckReconcilerTest {

  private final WorkflowRunMapper workflowRunMapper = mock(WorkflowRunMapper.class);
  private final WorkflowNodeRunMapper workflowNodeRunMapper = mock(WorkflowNodeRunMapper.class);
  private final WorkflowRunResumeProgressor resumeProgressor =
      mock(WorkflowRunResumeProgressor.class);
  private final BatchOrchestratorGovernanceProperties governance =
      mock(BatchOrchestratorGovernanceProperties.class);
  private final TimeoutEnforcerProperties timeoutProperties = mock(TimeoutEnforcerProperties.class);
  private final OrchestratorGracefulShutdown gracefulShutdown =
      mock(OrchestratorGracefulShutdown.class);
  private final MeterRegistry meterRegistry = mock(MeterRegistry.class);

  @AfterEach
  void clearTenantContext() {
    RlsTenantContextHolder.clear();
  }

  @Test
  void reconcilesEachCandidateUnderItsOwningTenantContext() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(governance.timeout()).thenReturn(timeoutProperties);
    when(timeoutProperties.getWorkflowStuckThresholdSeconds()).thenReturn(60L);
    when(timeoutProperties.getBatchSize()).thenReturn(10);
    when(workflowRunMapper.selectStuckRunningCandidates(any(), eq(10)))
        .thenReturn(List.of(candidate(1L, "tenant-a"), candidate(2L, "tenant-b")));
    when(workflowRunMapper.selectById("tenant-a", 1L)).thenAnswer(invocation -> {
      assertThat(RlsTenantContextHolder.get()).isEqualTo("tenant-a");
      return null;
    });
    when(workflowRunMapper.selectById("tenant-b", 2L)).thenAnswer(invocation -> {
      assertThat(RlsTenantContextHolder.get()).isEqualTo("tenant-b");
      return null;
    });
    org.mockito.Mockito.doAnswer(invocation -> {
      assertThat(RlsTenantContextHolder.get()).isEqualTo(invocation.getArgument(0));
      return null;
    }).when(resumeProgressor).resume(any(), any());

    reconciler().reconcile();

    verify(resumeProgressor).resume("tenant-a", 1L);
    verify(resumeProgressor).resume("tenant-b", 2L);
    assertThat(RlsTenantContextHolder.get()).isNull();
  }

  private WorkflowRunStuckReconciler reconciler() {
    return new WorkflowRunStuckReconciler(
        workflowRunMapper,
        workflowNodeRunMapper,
        resumeProgressor,
        governance,
        gracefulShutdown,
        meterRegistry);
  }

  private WorkflowRunEntity candidate(Long id, String tenantId) {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(id);
    run.setTenantId(tenantId);
    return run;
  }
}
