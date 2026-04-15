package com.example.batch.worker.core.infrastructure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ActiveTaskLeaseRegistry {

  private final Map<String, ActiveTaskLease> activeTaskLeases = new ConcurrentHashMap<>();

  // #1-3: 读写锁保护 register/remove 与 snapshot 的原子性，
  // 避免 shutdown 期间 snapshot 看到空集合而提前退出（TOCTOU）。
  private final ReadWriteLock shutdownLock = new ReentrantReadWriteLock();

  public void register(String taskId, String tenantId, String workerId) {
    if (taskId == null || tenantId == null || workerId == null) {
      return;
    }
    shutdownLock.readLock().lock();
    try {
      activeTaskLeases.put(taskId, new ActiveTaskLease(taskId, tenantId, workerId));
    } finally {
      shutdownLock.readLock().unlock();
    }
  }

  public void remove(String taskId) {
    if (taskId == null) {
      return;
    }
    shutdownLock.readLock().lock();
    try {
      activeTaskLeases.remove(taskId);
    } finally {
      shutdownLock.readLock().unlock();
    }
  }

  /**
   * 获取当前活跃租约的一致快照。
   *
   * <p>写锁保证在快照期间没有并发的 register/remove 操作，避免 TOCTOU。
   */
  public Collection<ActiveTaskLease> snapshot() {
    shutdownLock.writeLock().lock();
    try {
      return new ArrayList<>(activeTaskLeases.values());
    } finally {
      shutdownLock.writeLock().unlock();
    }
  }

  /** 返回当前活跃任务数（轻量级，不获取写锁）。 */
  public int size() {
    return activeTaskLeases.size();
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
