package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.orchestrator.application.scheduler.GlobalJobAdmission;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 平台全局活跃作业硬门禁。
 *
 * <p>新作业不能只执行 {@code count -> compare}，否则多个 Orchestrator 会一起读到最后一个空槽。
 * 正式准入先取得 PostgreSQL 事务级 advisory lock，并由调用事务在释放锁前把实例推进到 RUNNING。
 */
@Component
@RequiredArgsConstructor
class GlobalJobAdmissionGuard implements GlobalJobAdmission {

  private final JobInstanceMapper jobInstanceMapper;
  private final BatchOrchestratorGovernanceProperties governance;

  @Override
  public boolean hasCapacity() {
    if (!hasHardCap()) {
      return true;
    }
    jobInstanceMapper.acquireGlobalJobAdmissionLock();
    return hasObservedCapacity();
  }

  /** WAITING 候选排序只读快照；真正释放前必须在事务内再次调用 {@link #hasCapacity()}。 */
  @Override
  public boolean hasObservedCapacity() {
    long cap = governance.resourceScheduler().getGlobalMaxRunningJobs();
    return cap <= 0 || jobInstanceMapper.countActiveAll() < cap;
  }

  private boolean hasHardCap() {
    return governance.resourceScheduler().getGlobalMaxRunningJobs() > 0;
  }
}
