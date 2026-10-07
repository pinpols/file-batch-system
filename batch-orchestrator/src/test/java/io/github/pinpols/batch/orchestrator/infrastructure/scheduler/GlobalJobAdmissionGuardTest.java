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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("全局作业准入守卫;验证准入锁与活跃数读取顺序,全局上限判定以及上限关闭时的数据库访问省略")
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
  @DisplayName("开启全局上限时先获取准入锁再统计活跃作业数,保证并发判定不超卖")
  void shouldAcquireAdmissionLockBeforeCountingActiveJobs_whenCapEnabled() {
    properties.setGlobalMaxRunningJobs(10);
    when(mapper.countActiveAll()).thenReturn(9L);

    assertThat(guard.hasCapacity()).isTrue();

    var ordered = inOrder(mapper);
    ordered.verify(mapper).acquireGlobalJobAdmissionLock();
    ordered.verify(mapper).countActiveAll();
  }

  @Test
  @DisplayName("活跃作业数达到全局上限时拒绝新的准入请求")
  void shouldRejectAdmission_whenActiveJobsReachCap() {
    properties.setGlobalMaxRunningJobs(10);
    when(mapper.countActiveAll()).thenReturn(10L);

    assertThat(guard.hasCapacity()).isFalse();
  }

  @Test
  @DisplayName("全局上限关闭时直接放行,既不获取准入锁也不统计活跃作业数")
  void shouldSkipDatabaseAccess_whenGlobalCapDisabled() {
    properties.setGlobalMaxRunningJobs(0);

    assertThat(guard.hasCapacity()).isTrue();

    verify(mapper, never()).acquireGlobalJobAdmissionLock();
    verify(mapper, never()).countActiveAll();
  }
}
