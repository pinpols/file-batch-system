package io.github.pinpols.batch.console.domain.ops.entity;

import lombok.Data;

/**
 * console-api 只读 (build_id, sdk_version) 聚合行(SDK Phase 5 / SDK-P5-3,console Lane D)。
 *
 * <p>NULL build_id / sdk_version 在 SQL 层 COALESCE 为 {@code "(unknown)"} 字面量,便于 FE 直接渲染分组。
 */
@Data
public class WorkerFingerprintSummaryRow {

  private String buildId;
  private String sdkVersion;
  private Long count;
}
