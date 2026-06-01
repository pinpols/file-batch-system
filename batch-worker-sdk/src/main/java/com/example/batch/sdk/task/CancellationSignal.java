package com.example.batch.sdk.task;

/**
 * 单个 task 的取消信号 — SDK Phase 4 / SDK-P4-1。
 *
 * <p>由 {@code TaskDispatcher} 每个 task 执行期创建并注册;{@code LeaseRenewalScheduler} 读到 renew response 的
 * {@code cancelRequested=true}(平台运维 cancel 或 ORCH-P4-2 超时)或 lease 被回收(404/410)时翻转。handler 在长循环里调
 * {@link SdkTaskContext#isCancelled()} 感知,主动停 —— 不必等 60s lease 自然超时。
 *
 * <p>{@code volatile} 保证续约线程的写对执行线程立即可见。
 */
public final class CancellationSignal {

  private volatile boolean cancelled;

  public void cancel() {
    this.cancelled = true;
  }

  public boolean isCancelled() {
    return cancelled;
  }
}
