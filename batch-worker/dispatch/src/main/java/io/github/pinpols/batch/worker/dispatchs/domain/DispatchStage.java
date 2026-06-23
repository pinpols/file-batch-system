package io.github.pinpols.batch.worker.dispatchs.domain;

/** 分发 pipeline 的阶段枚举。 */
public enum DispatchStage {
  PREPARE,
  DISPATCH,
  ACK,
  RETRY,
  COMPENSATE,
  COMPLETE
}
