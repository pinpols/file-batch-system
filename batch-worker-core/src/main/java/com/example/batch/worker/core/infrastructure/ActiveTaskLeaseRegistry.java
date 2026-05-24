package com.example.batch.worker.core.infrastructure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 当前 worker 进程内 in-flight 任务的租约注册表。
 *
 * <p><b>两个用途</b>：
 *
 * <ul>
 *   <li>{@link WorkerTaskLeaseRenewer} 定时从 {@link #snapshot()} 取出所有活跃租约并向 Orchestrator 续期，防止因
 *       pipeline 执行时间较长被误判为失活而重新派发。
 *   <li>优雅停机时 {@link #awaitDrain} 等待所有任务自然完成后再退出进程，保证不留中间状态。
 * </ul>
 *
 * <p>{@code register}/{@code remove} 使用写锁、{@code snapshot} 使用读锁， 保证停机期间快照与写操作互斥，避免 TOCTOU
 * 窗口导致提前放行退出。
 */
@Slf4j
@Component
public class ActiveTaskLeaseRegistry {

  private final Map<String, ActiveTaskLease> activeTaskLeases = new ConcurrentHashMap<>();

  // P1-2: 已被 orchestrator REJECT 续租的 taskId（lease 已被驱逐，应中止当前执行，避免双执行）。
  // 由 WorkerTaskLeaseRenewer 在 DB CAS 返回 false 时调用 markLost。execute() report 前检查 isLost。
  // 注意：网络异常 / orchestrator 5xx 不算 lost（transient），不写入此集合。
  private final Set<String> lostLeases = ConcurrentHashMap.newKeySet();

  // #1-3: 读写锁保护 register/remove 与 snapshot 的原子性，
  // 避免 shutdown 期间 snapshot 看到空集合而提前退出（TOCTOU）。
  private final ReadWriteLock shutdownLock = new ReentrantReadWriteLock();

  // R-4.5: awaitDrain 用此 monitor 做 wait/notify，避免硬编码轮询间隔
  // （1000+ 活跃任务时 sleep(500) 会让实际等待时间远超 timeout）
  private final Object drainMonitor = new Object();

  // P1: remove(taskId) 时通知订阅者，让外围 per-taskId 累加 map（例如 WorkerTaskLeaseRenewer
  // 的 consecutiveFailures）能同步释放条目，避免任务自然结束后 AtomicInteger 永驻进程。
  // CopyOnWriteArrayList 适合 register 一次、read-mostly 的监听语义；listener 在 remove
  // 写锁释放后 fan-out 调用，single-task remove 的 listener 异常不影响其它 listener。
  private final List<Consumer<String>> removalListeners = new CopyOnWriteArrayList<>();

  /**
   * 注册 lease 移除监听器，{@link #remove(String)} 完成后按注册顺序回调（同步，调用线程内执行）。
   *
   * <p>典型用法：{@code WorkerTaskLeaseRenewer} 注册回调以清理 {@code consecutiveFailures}，
   * 防止任务自然完成后的失败计数器永驻进程。
   *
   * <p>listener 应保持轻量且不抛异常；异常会被吞掉并记 warn，避免破坏其它订阅者的回调链。
   */
  public void registerRemovalListener(Consumer<String> listener) {
    if (listener != null) {
      removalListeners.add(listener);
    }
  }

  // C-1.1: register/remove 修改状态 → 写锁；snapshot 只读 → 读锁
  public void register(String taskId, String tenantId, String workerId) {
    register(taskId, tenantId, workerId, null);
  }

  public void register(
      String taskId, String tenantId, String workerId, String partitionInvocationId) {
    if (taskId == null || tenantId == null || workerId == null) {
      return;
    }
    shutdownLock.writeLock().lock();
    try {
      activeTaskLeases.put(
          taskId, new ActiveTaskLease(taskId, tenantId, workerId, partitionInvocationId));
    } finally {
      shutdownLock.writeLock().unlock();
    }
  }

  public void remove(String taskId) {
    if (taskId == null) {
      return;
    }
    shutdownLock.writeLock().lock();
    try {
      activeTaskLeases.remove(taskId);
    } finally {
      shutdownLock.writeLock().unlock();
    }
    lostLeases.remove(taskId);
    // P1: 同步派发 listener，让外围 per-taskId 累加结构（如 consecutiveFailures）立即清理；
    // 任一 listener 异常不影响其它订阅者与 drain 通知逻辑。
    for (Consumer<String> listener : removalListeners) {
      try {
        listener.accept(taskId);
      } catch (RuntimeException ex) {
        log.warn("lease removal listener threw, taskId={}: {}", taskId, ex.getMessage());
      }
    }
    // R-4.5 + R3-P2-2: 每次 remove 都 notifyAll（不再仅在 isEmpty 时通知）。
    // 之前的"仅 isEmpty 时通知"在 awaitDrain 还没 wait 之前 notifyAll 会丢失，
    // awaitDrain 进入 wait 后只能等下一个 remove；如果当前 remove 已经让 map 空了，
    // awaitDrain 会等满 waitMillis 才再次检查，drain 报 timeout 假阴。
    // 改为每次 remove 都通知：所有等待者醒来后通过 while(!isEmpty) 循环重检，map
    // 仍非空就回到 wait；map 空就退出。多余的唤醒成本可忽略。
    synchronized (drainMonitor) {
      drainMonitor.notifyAll();
    }
  }

  /**
   * P1-2: 标记某 task 的 lease 已被 orchestrator 驱逐。仅在 renew 收到明确 REJECT（DB CAS 返回 false）时调用， 不在网络异常 /
   * 5xx 时调用——后者是 transient，应继续 retry。
   *
   * <p>由 {@link com.example.batch.worker.core.support.TaskExecutionWrapper#execute} 在 report 前 检查
   * {@link #isLost}：若已 lost，应放弃当前执行结果（orchestrator 已重新派发给别的 worker）。
   */
  public void markLost(String taskId) {
    if (taskId == null) {
      return;
    }
    if (activeTaskLeases.containsKey(taskId)) {
      lostLeases.add(taskId);
    }
  }

  public boolean isLost(String taskId) {
    return taskId != null && lostLeases.contains(taskId);
  }

  /**
   * 获取当前活跃租约的一致快照。
   *
   * <p>读锁保证快照期间不会有并发的 register/remove 操作，返回防御性拷贝确保线程安全。
   */
  public Collection<ActiveTaskLease> snapshot() {
    shutdownLock.readLock().lock();
    try {
      return new ArrayList<>(activeTaskLeases.values());
    } finally {
      shutdownLock.readLock().unlock();
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
   *
   * @return {@code true} 表示在超时前已干净排空，{@code false} 表示触发超时（仍有未完成任务）或被中断
   */
  public boolean awaitDrain(Duration timeout) {
    Duration effective = timeout == null ? Duration.ZERO : timeout;
    // 用单调钟 (nanoTime) 而非 currentTimeMillis,避免 NTP 时钟回拨期间 remaining 变负 →
    // 立即 break 误报 timeout=false
    long deadlineNanos = System.nanoTime() + Math.max(0L, effective.toNanos());
    synchronized (drainMonitor) {
      while (!activeTaskLeases.isEmpty()) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
          break;
        }
        try {
          // Object.wait 接受 (millis, nanos) — 拆分纳秒余数避免精度丢失
          long waitMillis = remainingNanos / 1_000_000L;
          int waitNanos = (int) (remainingNanos % 1_000_000L);
          drainMonitor.wait(waitMillis, waitNanos);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("awaitDrain interrupted; remainingLeases={}", activeTaskLeases.size());
          return false;
        }
      }
    }
    if (!activeTaskLeases.isEmpty()) {
      log.warn(
          "awaitDrain timeout; remainingLeases={}, timeoutMs={}",
          activeTaskLeases.size(),
          effective.toMillis());
      return false;
    }
    return true;
  }

  @Getter
  public static class ActiveTaskLease {

    private final String taskId;
    private final String tenantId;
    private final String workerId;
    private final String partitionInvocationId;

    public ActiveTaskLease(
        String taskId, String tenantId, String workerId, String partitionInvocationId) {
      this.taskId = taskId;
      this.tenantId = tenantId;
      this.workerId = workerId;
      this.partitionInvocationId = partitionInvocationId;
    }
  }
}
