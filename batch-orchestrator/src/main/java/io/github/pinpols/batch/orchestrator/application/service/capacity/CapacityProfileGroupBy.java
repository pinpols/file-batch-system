package io.github.pinpols.batch.orchestrator.application.service.capacity;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;

/** P2 cost profile 聚合维度。只做容量画像，不承载账单或成本分摊语义。 */
public enum CapacityProfileGroupBy {
  TENANT,
  JOB,
  WORKER;

  public static CapacityProfileGroupBy fromNullable(String raw) {
    if (raw == null || raw.isBlank()) {
      return TENANT;
    }
    try {
      return CapacityProfileGroupBy.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "groupBy must be one of TENANT, JOB, WORKER");
    }
  }
}
