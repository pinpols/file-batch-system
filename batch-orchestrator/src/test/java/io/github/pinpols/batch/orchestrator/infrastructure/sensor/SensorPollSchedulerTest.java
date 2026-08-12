package io.github.pinpols.batch.orchestrator.infrastructure.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.config.SensorProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SensorPollSchedulerTest {

  @Mock
  private SensorProbeTransactionExecutor transactionExecutor;

  @Mock
  private OrchestratorGracefulShutdown gracefulShutdown;

  @Mock
  private WorkflowRunMapper workflowRunMapper;

  @AfterEach
  void clearTenantContext() {
    RlsTenantContextHolder.clear();
  }

  @Test
  void drainingSkipsDatabaseScan() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    scheduler().scan();

    verify(transactionExecutor, never()).fetchDue(any(), anyInt());
  }

  @Test
  void probeRunsInsideResolvedTenantContextAndAlwaysCleansIt() {
    WorkflowNodeRunEntity nodeRun = new WorkflowNodeRunEntity();
    nodeRun.setId(7L);
    nodeRun.setWorkflowRunId(11L);
    WorkflowRunEntity workflowRun = new WorkflowRunEntity();
    workflowRun.setTenantId("tenant-a");
    when(transactionExecutor.fetchDue(any(Instant.class), anyInt())).thenReturn(List.of(nodeRun));
    when(workflowRunMapper.selectByIdAnyTenant(11L)).thenReturn(workflowRun);
    doAnswer(invocation -> {
          assertThat(RlsTenantContextHolder.get()).isEqualTo("tenant-a");
          return null;
        })
        .when(transactionExecutor)
        .probeOne(any(), any());

    scheduler().scan();

    verify(transactionExecutor).probeOne(any(), any());
    assertThat(RlsTenantContextHolder.get()).isNull();
  }

  private SensorPollScheduler scheduler() {
    return new SensorPollScheduler(
        transactionExecutor, new SensorProperties(), gracefulShutdown, workflowRunMapper);
  }
}
