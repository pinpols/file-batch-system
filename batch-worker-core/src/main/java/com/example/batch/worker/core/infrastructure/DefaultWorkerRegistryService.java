package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.WorkerRegistryClient;
import com.example.batch.worker.core.support.WorkerSelfRegistrationService;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Worker 自注册服务默认实现：将 {@link WorkerRegistryClient} 的远程调用封装为本地服务接口。
 *
 * <p>{@code register} 在远程响应缺少时间戳时以本地时间补齐，保证 {@code registeredAt} 和 {@code lastHeartbeatAt}
 * 始终非空；{@code renew} 心跳成功后刷新本地 {@code lastHeartbeatAt}。
 */
@Service("workerCoreWorkerRegistryService")
@RequiredArgsConstructor
public class DefaultWorkerRegistryService implements WorkerSelfRegistrationService {

  private final WorkerRegistryClient workerRegistryClient;

  @Override
  public WorkerRegistration register(WorkerRegistration registration) {
    WorkerRegistration registeredWorker = workerRegistryClient.register(registration);
    if (registeredWorker.getRegisteredAt() == null) {
      registeredWorker.setRegisteredAt(OffsetDateTime.now());
    }
    if (registeredWorker.getLastHeartbeatAt() == null) {
      registeredWorker.setLastHeartbeatAt(registeredWorker.getRegisteredAt());
    }
    return registeredWorker;
  }

  @Override
  public WorkerRegistration renew(WorkerRegistration registration) {
    WorkerRegistration heartbeatWorker = workerRegistryClient.heartbeat(registration);
    heartbeatWorker.setLastHeartbeatAt(OffsetDateTime.now());
    return heartbeatWorker;
  }

  @Override
  public void deactivate(WorkerRegistration registration) {
    workerRegistryClient.deactivate(registration);
  }

  @Override
  public WorkerRegistration updateStatus(WorkerRegistration registration, String status) {
    registration.setStatus(
        status == null || status.isBlank() ? WorkerRegistryStatus.ONLINE.code() : status);
    return workerRegistryClient.updateStatus(registration);
  }
}
