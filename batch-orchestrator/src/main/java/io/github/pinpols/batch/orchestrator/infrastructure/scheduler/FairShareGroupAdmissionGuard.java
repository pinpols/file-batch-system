package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 公平共享组的硬并发闸门。
 *
 * <p>共享组不能只做 {@code count -> compare}：多个派发事务会同时读到同一个余量而一起放行。本组件在计数前取得
 * PostgreSQL 事务级 advisory lock，因此调用方只要在同一事务内把实例推进到 {@code RUNNING}，检查和状态提交就
 * 是串行的。等待队列的预评估不持有事务时只能作为排序提示，真正 release 前必须再次调用本组件。
 */
@Component
@RequiredArgsConstructor
class FairShareGroupAdmissionGuard {

  private final JobInstanceMapper jobInstanceMapper;

  boolean hasCapacity(TenantQuotaPolicyEntity quotaPolicy) {
    boolean sharedGroupHardCapEnabled = quotaPolicy != null
        && Texts.hasText(quotaPolicy.fairShareGroup())
        && quotaPolicy.groupSharedMaxRunningJobs() != null
        && quotaPolicy.groupSharedMaxRunningJobs() > 0;
    if (!sharedGroupHardCapEnabled) {
      return true;
    }
    jobInstanceMapper.acquireFairShareGroupAdvisoryLock(quotaPolicy.fairShareGroup());
    return jobInstanceMapper.countActiveByFairShareGroup(quotaPolicy.fairShareGroup())
        < quotaPolicy.groupSharedMaxRunningJobs();
  }
}
