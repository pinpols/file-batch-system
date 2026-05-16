package com.example.batch.orchestrator.application.service.governance;

import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import java.util.List;

/**
 * Worker 排水治理服务接口，定义 Worker 节点的优雅下线与强制接管生命周期操作。
 *
 * <p>排水（Drain）流程允许 Worker 在超时期间自行完成已认领的任务后再下线； 超时后可通过 {@link #takeoverAfterDrainTimeout} 由
 * Orchestrator 强制接管残留任务。 {@link #forceOffline} 用于在无法等待时立即将 Worker 标记为下线状态， 而 {@link #takeover}
 * 则在不等待排水的情况下直接接管已认领任务，供运维应急使用。
 *
 * <p>所有操作均以租户 + Worker 编码为作用域，避免跨租户数据污染。
 */
public interface WorkerDrainGovernanceService {

  WorkerRegistryEntity startDrain(String tenantId, String workerCode, Integer timeoutSeconds);

  WorkerRegistryEntity forceOffline(String tenantId, String workerCode);

  WorkerRegistryEntity takeover(String tenantId, String workerCode);

  /**
   * 预热:把心跳超时被标 OFFLINE 的 worker 翻回 ONLINE,让 orchestrator 立即重新派发任务, 不必等下一轮心跳。
   *
   * <p>状态转移:OFFLINE → ONLINE;ONLINE 幂等返回;DRAINING / DECOMMISSIONED 抛 STATE_CONFLICT(需重新注册或等排空完成)。
   * 调用方应在确认 worker 进程实际仍存活(刚重启 / 网络瞬断恢复)时使用; 若 worker 真死,下一轮心跳超时检测会把它重新标回 OFFLINE。
   */
  WorkerRegistryEntity warmup(String tenantId, String workerCode);

  List<JobTaskEntity> listClaimedTasks(String tenantId, String workerCode);

  void takeoverAfterDrainTimeout(String tenantId, String workerCode);
}
