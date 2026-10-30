package io.github.pinpols.batch.worker.core.infrastructure;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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

  /** 仅更新发请求时的同一注册实例，迟到响应不能复活下线项或覆盖同标识的新注册。 */
  public boolean replaceIfCurrent(
      String workerId, WorkerRegistration expected, WorkerRegistration replacement) {
    if (EmptyChecks.isNull(workerId)
        || EmptyChecks.isNull(expected)
        || EmptyChecks.isNull(replacement)
        || !workerId.equals(replacement.getWorkerId())) {
      return false;
    }
    AtomicBoolean replaced = new AtomicBoolean();
    registrations.computeIfPresent(workerId, (key, current) -> {
      // 注册对象的字段可能相等，所有权仍须按实例身份区分。
      if (current != expected) {
        return current;
      }
      replaced.set(true);
      return replacement;
    });
    return replaced.get();
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
