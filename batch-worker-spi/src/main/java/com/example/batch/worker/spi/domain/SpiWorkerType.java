package com.example.batch.worker.spi.domain;

/** 专用 Task SPI worker 的 worker_type 常量(对齐 JobType.TASK)。 */
public final class SpiWorkerType {
  /** 原子任务 worker 类型。 */
  public static final String TASK = "TASK";

  private SpiWorkerType() {}
}
