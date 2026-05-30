package com.example.batch.common.spi.task;

/**
 * 资源类型枚举,供 {@link TaskCapability} 声明任务的资源占用倾向。
 *
 * <p>orchestrator 用本枚举做调度决策(避免把 NET-bound 任务全堆到同一 worker)+ worker 注册时按 kind 上报 quota。
 */
public enum ResourceKind {
  /** CPU-bound:计算密集,如数据加工。 */
  CPU,

  /** 网络 I/O:HTTP / SFTP / Kafka / 内部 RPC 等。 */
  NET,

  /** 磁盘 I/O:大文件读写 / 临时文件落盘。 */
  DISK,

  /** 数据库:SQL / 存过 / 高频读写 DB。 */
  DB,

  /** GPU:模型推理 / 图像处理(预留,当前 0 实现)。 */
  GPU
}
