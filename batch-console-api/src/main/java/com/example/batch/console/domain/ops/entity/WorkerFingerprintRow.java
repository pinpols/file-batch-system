package com.example.batch.console.domain.ops.entity;

import java.time.Instant;
import lombok.Data;

/**
 * console-api 只读 worker_registry 指纹行(SDK Phase 5 / SDK-P5-3,console Lane D)。
 *
 * <p>对应 V3 worker_registry 表字段:{@code heartbeat_at}(非 last_heartbeat_at)/ {@code process_id}(非
 * pid)。V163 新增 {@code build_id} / {@code sdk_version}(均可空 — 非 SDK worker 不上报)。
 */
@Data
public class WorkerFingerprintRow {

  private Long id;
  private String tenantId;
  private String workerCode;
  private String buildId;
  private String processId;
  private String sdkVersion;
  private String status;
  private Instant heartbeatAt;
}
