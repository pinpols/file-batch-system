package com.example.batch.orchestrator.application.service.sensor;

/** ADR-028 sensor 单次探测的结果状态。 */
public enum SensorProbeStatus {
  /** 条件已达成，sensor 节点推进到 SUCCESS。 */
  MATCHED,
  /** 条件未达成，继续下次轮询。 */
  NOT_YET,
  /** 外部资源不可用 / spec 解析失败 / 异常；重试 3 次后才标 FAILED。 */
  ERROR
}
