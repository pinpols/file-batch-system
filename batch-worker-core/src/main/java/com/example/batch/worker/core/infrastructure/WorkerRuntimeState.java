package com.example.batch.worker.core.infrastructure;

import com.example.batch.worker.core.domain.WorkerRegistration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Worker 进程内的注册状态仓库，以 workerId 为键维护当前节点所有已注册的 {@link WorkerRegistration}。 使用 {@link
 * java.util.concurrent.ConcurrentHashMap} 保证并发安全， 供心跳线程与注销流程共享访问，无需额外加锁。
 */
@Component
public class WorkerRuntimeState {

  private final Map<String, WorkerRegistration> registrations = new ConcurrentHashMap<>();

  public void put(WorkerRegistration registration) {
    registrations.put(registration.getWorkerId(), registration);
  }

  public WorkerRegistration get(String workerId) {
    return registrations.get(workerId);
  }

  public WorkerRegistration remove(String workerId) {
    return registrations.remove(workerId);
  }

  public Collection<WorkerRegistration> snapshot() {
    return registrations.values();
  }
}
