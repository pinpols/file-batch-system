package com.example.batch.worker.core.support;

/**
 * Worker 实例当前负载提供者. 由 {@link AbstractTaskConsumer} 子类实现, {@code DefaultHeartbeatService}
 * 在心跳时收集所有实现的 {@link #currentLoad()} 求和写入 {@code WorkerRegistration.currentLoad}, 让 orch 派发侧能看到
 * worker 实际并发水位 (least-loaded 调度的输入).
 *
 * <p>背景: 此前 {@code WorkerRegistration.currentLoad} 永远是 0 (只做 null→0 回退, 无人写入实际负载), orch 无法基于 worker
 * 实际并发做 least-loaded 调度. 详见 {@code docs/analysis/worker-vs-industry-2026-05-03.md} P2-12.
 *
 * <p>语义: 返回当前正在执行的 task 数 (0 表示空闲, maxConcurrentTasks 表示满载). 实现应**无副作用** + **轻量** — 心跳路径每 ~10s 调一次,
 * 不能阻塞或加锁.
 */
public interface WorkerLoadProvider {

  /** 当前正在执行的 task 数; 实现保证非负. */
  int currentLoad();
}
