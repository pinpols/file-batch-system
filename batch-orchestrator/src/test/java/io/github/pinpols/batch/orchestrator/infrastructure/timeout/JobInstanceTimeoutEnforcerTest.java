package io.github.pinpols.batch.orchestrator.infrastructure.timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.orchestrator.application.service.task.JobInstanceTerminalStatusApplicationService;
import io.github.pinpols.batch.orchestrator.config.TimeoutEnforcerProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 单元测试：{@link JobInstanceTimeoutEnforcer} 仅终止 mapper 已判定为实际执行超时的实例。 */
@ExtendWith(MockitoExtension.class)
class JobInstanceTimeoutEnforcerTest {

  @Mock
  private JobInstanceMapper jobInstanceMapper;

  @Mock
  private BatchOrchestratorGovernanceProperties governance;

  @Mock
  private OrchestratorGracefulShutdown gracefulShutdown;

  @Mock
  private JobInstanceTerminalStatusApplicationService terminalStatusService;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final TimeoutEnforcerProperties timeoutProperties = new TimeoutEnforcerProperties();
  private JobInstanceTimeoutEnforcer enforcer;

  @BeforeEach
  void setUp() {
    enforcer = new JobInstanceTimeoutEnforcer(
        jobInstanceMapper, governance, gracefulShutdown, meterRegistry, terminalStatusService);
  }

  @Test
  void skipsScanWhenDraining() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    enforcer.enforce();

    verify(jobInstanceMapper, never()).selectTimedOutCandidates(anyInt());
  }

  @Test
  void marksOnlyMapperSelectedExecutionTimeout() {
    JobInstanceEntity candidate = new JobInstanceEntity();
    candidate.setId(1L);
    candidate.setTenantId("t1");
    candidate.setJobCode("job-a");
    candidate.setVersion(3L);
    candidate.setDryRun(false);
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(governance.timeout()).thenReturn(timeoutProperties);
    when(jobInstanceMapper.selectTimedOutCandidates(anyInt())).thenReturn(List.of(candidate));
    when(terminalStatusService.updateTerminalStatusAndReconcileChildren(any())).thenReturn(1);

    enforcer.enforce();

    verify(terminalStatusService).updateTerminalStatusAndReconcileChildren(any());
    assertThat(meterRegistry.counter("batch.timeout.enforcer.failed.total").count())
        .isEqualTo(1.0);
    assertThat(JobInstanceStatus.FAILED.code()).isEqualTo("FAILED");
  }
}
