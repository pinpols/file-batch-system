package com.example.batch.worker.core.infrastructure;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ActiveTaskLeaseRegistry {

  private final Map<String, ActiveTaskLease> activeTaskLeases = new ConcurrentHashMap<>();

  public void register(String taskId, String tenantId, String workerId) {
    if (taskId == null || tenantId == null || workerId == null) {
      return;
    }
    activeTaskLeases.put(taskId, new ActiveTaskLease(taskId, tenantId, workerId));
  }

  public void remove(String taskId) {
    if (taskId == null) {
      return;
    }
    activeTaskLeases.remove(taskId);
  }

  public Collection<ActiveTaskLease> snapshot() {
    return activeTaskLeases.values();
  }

  /**
   * 等待 in-flight 任务排空（best-effort）。
   *
   * <p>语义约束：
   *
   * <ul>
   *   <li>只用于优雅停机阶段：停止拉取新任务后，等待已有任务自然结束
   *   <li>不抛异常：避免 shutdown hook 因异常导致更糟的退出路径
   *   <li>超时返回：保证不会无限阻塞进程退出
   * </ul>
   */
  public void awaitDrain(Duration timeout) {
    Duration effective = timeout == null ? Duration.ZERO : timeout;
    long deadline = System.currentTimeMillis() + Math.max(0L, effective.toMillis());
    while (!snapshot().isEmpty() && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("awaitDrain interrupted; remainingLeases={}", snapshot().size());
        return;
      }
    }
    if (!snapshot().isEmpty()) {
      log.warn(
          "awaitDrain timeout; remainingLeases={}, timeoutMs={}",
          snapshot().size(),
          effective.toMillis());
    }
  }

  @Getter
  public static class ActiveTaskLease {

    private final String taskId;
    private final String tenantId;
    private final String workerId;

    public ActiveTaskLease(String taskId, String tenantId, String workerId) {
      this.taskId = taskId;
      this.tenantId = tenantId;
      this.workerId = workerId;
    }
  }
}
