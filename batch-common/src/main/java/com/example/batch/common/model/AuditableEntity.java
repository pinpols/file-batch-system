package com.example.batch.common.model;

import java.time.Instant;
import lombok.Data;

@Data
public abstract class AuditableEntity {

  private String tenantId;
  private String createdBy;
  private String updatedBy;
  private Instant createdAt;
  private Instant updatedAt;
}
