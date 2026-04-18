package com.example.batch.worker.core.infrastructure;

import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.HeartbeatService;
import com.example.batch.worker.core.support.WorkerSelfRegistrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Worker 心跳服务默认实现：从 {@link WorkerRuntimeState} 取出当前注册信息，
 * 调用 {@link WorkerSelfRegistrationService#renew} 向 Orchestrator 续期，并将更新后的注册状态写回缓存。
 *
 * <p>若 workerId 对应的注册不存在（如 Worker 尚未完成启动注册），静默跳过本次心跳。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultHeartbeatService implements HeartbeatService {

  private final WorkerSelfRegistrationService workerRegistryService;
  private final WorkerRuntimeState workerRuntimeState;

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
    if (activeRegistration.getCurrentLoad() == null) {
      activeRegistration.setCurrentLoad(0);
    }
    activeRegistration = workerRegistryService.renew(activeRegistration);
    workerRuntimeState.put(activeRegistration);
    log.debug("worker heartbeat: workerId={}", workerId);
  }
}
