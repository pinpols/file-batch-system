package com.example.batch.worker.atomic.domain;

/** 专用 Task SPI worker 的 worker_type 常量(对齐 JobType.ATOMIC)。 */
public final class AtomicWorkerType {
  /** 原子任务 worker 类型。 */
  public static final String ATOMIC = "ATOMIC";

  private AtomicWorkerType() {}
}
