package com.example.batch.worker.core.infrastructure;

import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.HeartbeatService;
import com.example.batch.worker.core.support.WorkerLoadProvider;
import com.example.batch.worker.core.support.WorkerSelfRegistrationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Worker 心跳服务默认实现：从 {@link WorkerRuntimeState} 取出当前注册信息， 调用 {@link
 * WorkerSelfRegistrationService#renew} 向 Orchestrator 续期，并将更新后的注册状态写回缓存。
 *
 * <p>P2-12: 心跳前从 {@link WorkerLoadProvider} 收集当前正在执行的 task 数（{@code AbstractTaskConsumer} 实现 =
 * maxConcurrentTasks - semaphore.availablePermits()），多个 consumer 求和后写入 {@code
 * WorkerRegistration.currentLoad}，让 orch 派发侧能看到 worker 实际并发水位。
 *
 * <p>若 workerId 对应的注册不存在（如 Worker 尚未完成启动注册），静默跳过本次心跳。
 */
@Slf4j
@Service
public class DefaultHeartbeatService implements HeartbeatService {

  private final WorkerSelfRegistrationService workerRegistryService;
  private final WorkerRuntimeState workerRuntimeState;
  private final ObjectProvider<WorkerLoadProvider> loadProviders;

  public DefaultHeartbeatService(
      WorkerSelfRegistrationService workerRegistryService,
      WorkerRuntimeState workerRuntimeState,
      ObjectProvider<WorkerLoadProvider> loadProviders) {
    this.workerRegistryService = workerRegistryService;
    this.workerRuntimeState = workerRuntimeState;
    this.loadProviders = loadProviders;
  }

  @Override
  public void beat(String workerId) {
    if (workerId == null || workerId.isBlank()) {
      return;
    }
    WorkerRegistration activeRegistration = workerRuntimeState.get(workerId);
    if (activeRegistration == null) {
      log.debug("skip heartbeat for unknown workerId={}", workerId);
      return;
    }
    activeRegistration.setCurrentLoad(collectCurrentLoad());
    activeRegistration = workerRegistryService.renew(activeRegistration);
    workerRuntimeState.put(activeRegistration);
    log.debug(
        "worker heartbeat: workerId={} currentLoad={}",
        workerId,
        activeRegistration.getCurrentLoad());
  }

  /** 求和所有 WorkerLoadProvider 实现 (通常是 1 个 AbstractTaskConsumer 子类), 异常静默兜底为 0. */
  private int collectCurrentLoad() {
    try {
      return loadProviders.stream().mapToInt(WorkerLoadProvider::currentLoad).sum();
    } catch (RuntimeException ex) {
      log.warn("WorkerLoadProvider currentLoad sum failed; falling back to 0: {}", ex.getMessage());
      return 0;
    }
  }
}
