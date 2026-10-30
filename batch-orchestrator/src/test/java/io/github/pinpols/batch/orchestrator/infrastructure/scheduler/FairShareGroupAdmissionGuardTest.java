package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("公平份额分组准入守卫:验证准入校验的加锁顺序,硬上限拒绝与只读容量观测")
@ExtendWith(MockitoExtension.class)
class FairShareGroupAdmissionGuardTest {

  @Mock
  private JobInstanceMapper jobInstanceMapper;

  private FairShareGroupAdmissionGuard guard;

  @Test
  @DisplayName("校验准入容量时先获取分组咨询锁再统计在用实例数")
  void shouldAcquireAdvisoryLockBeforeCounting_whenCheckingCapacity() {
    TenantQuotaPolicyEntity policy = groupPolicy(3);
    when(jobInstanceMapper.countActiveByFairShareGroup("settlement")).thenReturn(2L);

    assertThat(guard.hasCapacity(policy)).isTrue();

    InOrder order = inOrder(jobInstanceMapper);
    order.verify(jobInstanceMapper).acquireFairShareGroupAdvisoryLock("settlement");
    order.verify(jobInstanceMapper).countActiveByFairShareGroup("settlement");
  }

  @Test
  @DisplayName("在用实例数达到硬上限时拒绝继续准入")
  void shouldDenyAdmission_whenActiveCountReachesHardCap() {
    when(jobInstanceMapper.countActiveByFairShareGroup("settlement")).thenReturn(3L);

    assertThat(guard.hasCapacity(groupPolicy(3))).isFalse();
  }

  @Test
  @DisplayName("只读观测容量时返回可用结论且不获取准入锁")
  void shouldReportCapacityWithoutLock_whenObservingOnly() {
    when(jobInstanceMapper.countActiveByFairShareGroup("settlement")).thenReturn(2L);

    assertThat(guard.hasObservedCapacity(groupPolicy(3))).isTrue();

    verify(jobInstanceMapper, never()).acquireFairShareGroupAdvisoryLock("settlement");
  }

  private static TenantQuotaPolicyEntity groupPolicy(int cap) {
    return new TenantQuotaPolicyEntity(
        1L, "tenant-a", "fair", 0, 0, 0, 1, "settlement", 0, 0, "NONE", cap, true, "QUEUE_DEFER");
  }

  @BeforeEach
  void initializeSubject() {
    guard = new FairShareGroupAdmissionGuard(jobInstanceMapper);
  }
}
