package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class FairShareGroupAdmissionGuardTest {

  private final JobInstanceMapper jobInstanceMapper = mock(JobInstanceMapper.class);
  private final FairShareGroupAdmissionGuard guard =
      new FairShareGroupAdmissionGuard(jobInstanceMapper);

  @Test
  void acquiresTransactionLockBeforeReadingGroupUsage() {
    TenantQuotaPolicyEntity policy = groupPolicy(3);
    when(jobInstanceMapper.countActiveByFairShareGroup("settlement")).thenReturn(2L);

    assertThat(guard.hasCapacity(policy)).isTrue();

    InOrder order = inOrder(jobInstanceMapper);
    order.verify(jobInstanceMapper).acquireFairShareGroupAdvisoryLock("settlement");
    order.verify(jobInstanceMapper).countActiveByFairShareGroup("settlement");
  }

  @Test
  void deniesWhenActiveInstancesReachHardCap() {
    when(jobInstanceMapper.countActiveByFairShareGroup("settlement")).thenReturn(3L);

    assertThat(guard.hasCapacity(groupPolicy(3))).isFalse();
  }

  private static TenantQuotaPolicyEntity groupPolicy(int cap) {
    return new TenantQuotaPolicyEntity(
        1L, "tenant-a", "fair", 0, 0, 0, 1, "settlement", 0, 0, "NONE", cap, true, "QUEUE_DEFER");
  }
}
