package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import org.junit.jupiter.api.Test;

class GlobalJobAdmissionGuardTest {

  private final JobInstanceMapper mapper = mock(JobInstanceMapper.class);
  private final BatchOrchestratorGovernanceProperties governance =
      mock(BatchOrchestratorGovernanceProperties.class);
  private final ResourceSchedulerProperties properties = new ResourceSchedulerProperties();
  private final GlobalJobAdmissionGuard guard = new GlobalJobAdmissionGuard(mapper, governance);

  GlobalJobAdmissionGuardTest() {
    when(governance.resourceScheduler()).thenReturn(properties);
  }

  @Test
  void hardAdmissionLocksBeforeReadingActiveCount() {
    properties.setGlobalMaxRunningJobs(10);
    when(mapper.countActiveAll()).thenReturn(9L);

    assertThat(guard.hasCapacity()).isTrue();

    var ordered = inOrder(mapper);
    ordered.verify(mapper).acquireGlobalJobAdmissionLock();
    ordered.verify(mapper).countActiveAll();
  }

  @Test
  void hardAdmissionRejectsWhenCapIsReached() {
    properties.setGlobalMaxRunningJobs(10);
    when(mapper.countActiveAll()).thenReturn(10L);

    assertThat(guard.hasCapacity()).isFalse();
  }

  @Test
  void disabledCapAvoidsDatabaseRoundTrip() {
    properties.setGlobalMaxRunningJobs(0);

    assertThat(guard.hasCapacity()).isTrue();

    verify(mapper, never()).acquireGlobalJobAdmissionLock();
    verify(mapper, never()).countActiveAll();
  }
}
