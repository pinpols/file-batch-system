package io.github.pinpols.batch.worker.core.infrastructure;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 当前 Worker 进程内已认领、待执行或正在执行任务的租约注册表。
 *
 * <p><b>两个用途</b>：
 *
 * <ul>
 *   <li>{@link WorkerTaskLeaseRenewer} 定时从 {@link #snapshot()} 取出所有租约并向 Orchestrator 续期，防止批量 CLAIM
 *       中排队的任务或运行时间较长的 pipeline 被误判为失活并重新派发。
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

  // 平台取消请求到达后,由 renewer 调用 requestCancellation；wrapper 在 submit future 后
  // 注册回调。二者可能乱序,所以用 requestedCancellations 暂存先到的取消信号。
  private final Map<String, Runnable> cancellationCallbacks = new ConcurrentHashMap<>();
  private final Set<String> requestedCancellations = ConcurrentHashMap.newKeySet();

  // 已完成业务执行、正在上报结果的 taskId。仍保留在 activeTaskLeases 中供优雅停机 drain 等待，
  // 但不再进入 renew snapshot，避免“任务已完成后续租旧快照”误报为 lease lost。
  private final Set<String> completingLeases = ConcurrentHashMap.newKeySet();

  // P1-2: 已被 orchestrator REJECT 续租的 taskId（lease 已被驱逐，应中止当前执行，避免双执行）。
  // 由 WorkerTaskLeaseRenewer 在 DB CAS 返回 false 时调用 markLost。execute() report 前调用
  // markCompletingUnlessLost。
  // 注意：网络异常 / orchestrator 5xx 不算 lost（transient），不写入此集合。
  private final Set<String> lostLeases = ConcurrentHashMap.newKeySet();

  // #1-3: 读写锁保护 register/remove 与 snapshot 的原子性，
  // 避免 shutdown 期间 snapshot 看到空集合而提前退出（TOCTOU）。
  private final ReadWriteLock shutdownLock = new ReentrantReadWriteLock();

  // R-4.5: awaitDrain 用此 monitor 做 wait/notify，避免硬编码轮询间隔
  // （1000+ 活跃任务时 sleep(500) 会让实际等待时间远超 timeout）
  private final Object drainMonitor = new Object();

  private final LongSupplier nanoTime;

  public ActiveTaskLeaseRegistry() {
    this(System::nanoTime);
  }

  ActiveTaskLeaseRegistry(LongSupplier nanoTime) {
    this.nanoTime = nanoTime;
  }

  // P1: remove(taskId) 时通知订阅者，让外围 per-taskId 累加 map（例如 WorkerTaskLeaseRenewer
  // 的 consecutiveFailures）能同步释放条目，避免任务自然结束后 AtomicInteger 永驻进程。
  // CopyOnWriteArrayList 适合 register 一次、read-mostly 的监听语义；listener 在 remove
  // 写锁释放后 fan-out 调用，single-task remove 的 listener 异常不影响其它 listener。
  private final List<Consumer<String>> removalListeners = new CopyOnWriteArrayList<>();
  private final List<Consumer<ActiveTaskLease>> leaseRemovalListeners =
      new CopyOnWriteArrayList<>();

  /** 注册兼容 taskId 级别的移除监听器；并发状态应优先使用带 lease 身份的监听器。 */
  public void registerRemovalListener(Consumer<String> listener) {
    if (listener != null) {
      removalListeners.add(listener);
    }
  }

  /** 注册带具体租约身份的移除监听器，避免旧租约清理新 invocation 的状态。 */
  public void registerLeaseRemovalListener(Consumer<ActiveTaskLease> listener) {
    if (EmptyChecks.isNotNull(listener)) {
      leaseRemovalListeners.add(listener);
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
      completingLeases.remove(taskId);
      lostLeases.remove(taskId);
      requestedCancellations.remove(taskId);
      cancellationCallbacks.remove(taskId);
      activeTaskLeases.put(
          taskId, new ActiveTaskLease(taskId, tenantId, workerId, partitionInvocationId));
    } finally {
      shutdownLock.writeLock().unlock();
    }
  }

  /**
   * 登记批量 CLAIM 已成功、尚在当前 poll 等待执行的任务。重复登记相同租约不重置续租/取消/丢失状态；
   * 同 taskId 对应不同 invocation 或已开始执行时拒绝，避免新 claim 覆盖进程内现有所有权。
   */
  public boolean registerClaimed(
      String taskId, String tenantId, String workerId, String partitionInvocationId) {
    return registerClaimedAt(
        taskId, tenantId, workerId, partitionInvocationId, nanoTime.getAsLong());
  }

  /** 登记 CLAIM 请求开始时采集的单调时钟，令远程调用和返回后暂停都计入租约年龄。 */
  boolean registerClaimedAt(
      String taskId,
      String tenantId,
      String workerId,
      String partitionInvocationId,
      long claimedAtNanos) {
    if (EmptyChecks.isNull(taskId)
        || EmptyChecks.isNull(tenantId)
        || EmptyChecks.isNull(workerId)) {
      return false;
    }
    shutdownLock.writeLock().lock();
    try {
      ActiveTaskLease existing = activeTaskLeases.get(taskId);
      if (EmptyChecks.isNotNull(existing)) {
        return sameOwner(existing, tenantId, workerId, partitionInvocationId)
            && !existing.isExecutionStarted();
      }
      activeTaskLeases.put(
          taskId,
          new ActiveTaskLease(
              taskId, tenantId, workerId, partitionInvocationId, false, claimedAtNanos));
      completingLeases.remove(taskId);
      lostLeases.remove(taskId);
      requestedCancellations.remove(taskId);
      cancellationCallbacks.remove(taskId);
      return true;
    } finally {
      shutdownLock.writeLock().unlock();
    }
  }

  /** 单调时钟入口供 CLAIM 调用方在网络往返前记录租约年龄起点。 */
  long currentNanos() {
    return nanoTime.getAsLong();
  }

  /**
   * 将已登记 claim 转为执行中；单条 CLAIM 则在此首次登记。相同 taskId 只能有一个执行调用取得启动权。
   */
  public boolean beginExecution(
      String taskId, String tenantId, String workerId, String partitionInvocationId) {
    if (EmptyChecks.isNull(taskId)
        || EmptyChecks.isNull(tenantId)
        || EmptyChecks.isNull(workerId)) {
      return false;
    }
    shutdownLock.writeLock().lock();
    try {
      ActiveTaskLease existing = activeTaskLeases.get(taskId);
      if (EmptyChecks.isNull(existing)) {
        long executionStartedNanos = nanoTime.getAsLong();
        ActiveTaskLease newLease = new ActiveTaskLease(
            taskId, tenantId, workerId, partitionInvocationId, true, executionStartedNanos);
        activeTaskLeases.put(taskId, newLease);
        completingLeases.remove(taskId);
        lostLeases.remove(taskId);
        requestedCancellations.remove(taskId);
        cancellationCallbacks.remove(taskId);
        return true;
      }
      return sameOwner(existing, tenantId, workerId, partitionInvocationId)
          && existing.startExecution();
    } finally {
      shutdownLock.writeLock().unlock();
    }
  }

  /** 续租成功后更新时间戳，供任务排队后在启动前判断是否需要同步确认所有权。 */
  public void markRenewed(String taskId) {
    ActiveTaskLease lease = EmptyChecks.isNull(taskId) ? null : activeTaskLeases.get(taskId);
    if (EmptyChecks.isNotNull(lease)) {
      lease.markRenewed(nanoTime.getAsLong());
    }
  }

  /** 只刷新发起续租请求时的租约实例，忽略已被替换的旧快照响应。 */
  public boolean markRenewedIfCurrent(ActiveTaskLease lease) {
    if (EmptyChecks.isNull(lease)) {
      return false;
    }
    String taskId = lease.getTaskId();
    boolean leaseIsNoLongerCurrent = activeTaskLeases.get(taskId) != lease;
    if (leaseIsNoLongerCurrent) {
      return false;
    }
    lease.markRenewed(nanoTime.getAsLong());
    return true;
  }

  /** 没有在给定窗口内得到 CLAIM/续租确认时返回 true。 */
  public boolean isRenewalDue(String taskId, long maxAgeMillis) {
    ActiveTaskLease lease = EmptyChecks.isNull(taskId) ? null : activeTaskLeases.get(taskId);
    if (EmptyChecks.isNull(lease)) {
      return true;
    }
    long normalizedMaxAgeMillis = Math.max(0L, maxAgeMillis);
    long maxAgeNanos = normalizedMaxAgeMillis > Long.MAX_VALUE / 1_000_000L
        ? Long.MAX_VALUE
        : normalizedMaxAgeMillis * 1_000_000L;
    return nanoTime.getAsLong() - lease.lastConfirmedNanosValue() > maxAgeNanos;
  }

  private static boolean sameOwner(
      ActiveTaskLease lease, String tenantId, String workerId, String partitionInvocationId) {
    return tenantId.equals(lease.getTenantId())
        && workerId.equals(lease.getWorkerId())
        && Objects.equals(partitionInvocationId, lease.getPartitionInvocationId());
  }

  public void remove(String taskId) {
    if (taskId == null) {
      return;
    }
    ActiveTaskLease removedLease;
    shutdownLock.writeLock().lock();
    try {
      removedLease = activeTaskLeases.remove(taskId);
      completingLeases.remove(taskId);
      lostLeases.remove(taskId);
      requestedCancellations.remove(taskId);
      cancellationCallbacks.remove(taskId);
    } finally {
      shutdownLock.writeLock().unlock();
    }
    // P1: 同步派发 listener，让外围 per-taskId 累加结构（如 consecutiveFailures）立即清理；
    // 任一 listener 异常不影响其它订阅者与 drain 通知逻辑。
    for (Consumer<String> listener : removalListeners) {
      try {
        listener.accept(taskId);
      } catch (RuntimeException ex) {
        log.warn(
            "lease removal listener threw, taskId={}: {}",
            taskId,
            SwallowedExceptionLogger.summary(ex));
      }
    }
    if (EmptyChecks.isNotNull(removedLease)) {
      for (Consumer<ActiveTaskLease> listener : leaseRemovalListeners) {
        try {
          listener.accept(removedLease);
        } catch (RuntimeException ex) {
          log.warn(
              "lease removal listener threw, taskId={}: {}",
              taskId,
              SwallowedExceptionLogger.summary(ex));
        }
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
   * 注册运行中任务的取消回调。若取消请求已先到达,注册后立即触发一次。
   *
   * <p>回调必须幂等；当前调用方用 {@code Future.cancel(true)}，重复触发只会返回 false。
   */
  public void registerCancellationCallback(String taskId, Runnable callback) {
    if (taskId == null || callback == null) {
      return;
    }
    cancellationCallbacks.put(taskId, callback);
    if (requestedCancellations.contains(taskId)) {
      invokeCancellation(taskId, callback);
    }
  }

  /** 平台已请求取消 task：触发运行中回调；若回调尚未注册,先暂存。 */
  public boolean requestCancellation(String taskId) {
    if (taskId == null) {
      return false;
    }
    return requestCancellationIfCurrent(activeTaskLeases.get(taskId));
  }

  /** 只把续租快照中的取消要求应用到仍持有该租约实例的 invocation。 */
  public boolean requestCancellationIfCurrent(ActiveTaskLease lease) {
    if (EmptyChecks.isNull(lease)) {
      return false;
    }
    String taskId = lease.getTaskId();
    Runnable callback;
    shutdownLock.writeLock().lock();
    try {
      if (activeTaskLeases.get(taskId) != lease) {
        return false;
      }
      requestedCancellations.add(taskId);
      callback = cancellationCallbacks.get(taskId);
    } finally {
      shutdownLock.writeLock().unlock();
    }
    if (callback != null) {
      invokeCancellation(taskId, callback);
    }
    return true;
  }

  public boolean isCancellationRequested(String taskId) {
    return taskId != null && requestedCancellations.contains(taskId);
  }

  private void invokeCancellation(String taskId, Runnable callback) {
    try {
      callback.run();
    } catch (RuntimeException ex) {
      log.warn(
          "task cancellation callback threw, taskId={}: {}",
          taskId,
          SwallowedExceptionLogger.summary(ex));
    }
  }

  /**
   * P1-2: 标记某 task 的 lease 已被 orchestrator 驱逐。仅在 renew 收到明确 REJECT（DB CAS 返回 false）时调用， 不在网络异常 /
   * 5xx 时调用——后者是 transient，应继续 retry。
   *
   * <p>由 {@link io.github.pinpols.batch.worker.core.support.TaskExecutionWrapper#execute} 在 report
   * 前 检查 {@link #isLost}：若已 lost，应放弃当前执行结果（orchestrator 已重新派发给别的 worker）。
   */
  public void markLost(String taskId) {
    if (taskId == null) {
      return;
    }
    markLostIfCurrent(activeTaskLeases.get(taskId));
  }

  /** 只将仍为当前项的续租快照标记为 lost，避免旧响应污染同 taskId 的新 invocation。 */
  public boolean markLostIfCurrent(ActiveTaskLease lease) {
    if (EmptyChecks.isNull(lease)) {
      return false;
    }
    String taskId = lease.getTaskId();
    shutdownLock.writeLock().lock();
    try {
      if (activeTaskLeases.get(taskId) != lease || completingLeases.contains(taskId)) {
        return false;
      }
      lostLeases.add(taskId);
      return true;
    } finally {
      shutdownLock.writeLock().unlock();
    }
  }

  public boolean isLost(String taskId) {
    return taskId != null && lostLeases.contains(taskId);
  }

  /**
   * 进入结果上报阶段。
   *
   * <p>该方法和 {@link #markLost(String)} 共用写锁，保证“续租拒绝标 lost”和“任务完成准备 report”有确定顺序：
   *
   * <ul>
   *   <li>lost 先发生：返回 false，wrapper 放弃 report。
   *   <li>completion 先发生：后续旧 renew 快照的拒绝不会再把任务标 lost。
   * </ul>
   *
   * <p>completing lease 仍留在 activeTaskLeases 中，所以优雅停机会继续等待 report 完成后的 {@link #remove(String)}。
   */
  public boolean markCompletingUnlessLost(String taskId) {
    if (taskId == null) {
      return false;
    }
    shutdownLock.writeLock().lock();
    try {
      if (!activeTaskLeases.containsKey(taskId) || lostLeases.contains(taskId)) {
        return false;
      }
      completingLeases.add(taskId);
      return true;
    } finally {
      shutdownLock.writeLock().unlock();
    }
  }

  /**
   * 获取当前活跃租约的一致快照。
   *
   * <p>读锁保证快照期间不会有并发的 register/remove 操作，返回防御性拷贝确保线程安全。
   */
  public Collection<ActiveTaskLease> snapshot() {
    shutdownLock.readLock().lock();
    try {
      return activeTaskLeases.values().stream()
          .filter(lease -> !completingLeases.contains(lease.getTaskId()))
          .toList();
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
   *   <li>超时返回：保证不会无限阻写入程退出
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

    private final AtomicBoolean executionStarted;

    private final AtomicLong lastConfirmedNanos;

    public ActiveTaskLease(
        String taskId, String tenantId, String workerId, String partitionInvocationId) {
      this(taskId, tenantId, workerId, partitionInvocationId, true, System.nanoTime());
    }

    private ActiveTaskLease(
        String taskId,
        String tenantId,
        String workerId,
        String partitionInvocationId,
        boolean executionStarted,
        long lastConfirmedNanos) {
      this.taskId = taskId;
      this.tenantId = tenantId;
      this.workerId = workerId;
      this.partitionInvocationId = partitionInvocationId;
      this.executionStarted = new AtomicBoolean(executionStarted);
      this.lastConfirmedNanos = new AtomicLong(lastConfirmedNanos);
    }

    private boolean isExecutionStarted() {
      return executionStarted.get();
    }

    private boolean startExecution() {
      return executionStarted.compareAndSet(false, true);
    }

    private long lastConfirmedNanosValue() {
      return lastConfirmedNanos.get();
    }

    private void markRenewed(long nowNanos) {
      lastConfirmedNanos.set(nowNanos);
    }
  }
}
