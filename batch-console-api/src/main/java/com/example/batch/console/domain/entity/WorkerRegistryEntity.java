package com.example.batch.console.domain.entity;

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
  private Instant drainStartedAt;
  private Instant drainDeadlineAt;
}
