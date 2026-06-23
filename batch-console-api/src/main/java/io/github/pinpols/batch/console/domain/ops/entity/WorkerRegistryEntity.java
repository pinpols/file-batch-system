package io.github.pinpols.batch.console.domain.ops.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class WorkerRegistryEntity {

  private Long id;
  private String tenantId;
  private String workerCode;
  private String workerGroup;
  private String status;
  private Instant heartbeatAt;
  private Integer currentLoad;
  private Integer maxConcurrent;
  private Instant drainStartedAt;
  private Instant drainDeadlineAt;
}
