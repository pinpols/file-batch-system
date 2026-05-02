package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class ArchivePolicyEntity {

  private Long id;

  private String tenantId;

  private String targetTable;

  private Integer retentionDays;

  private Boolean archiveEnabled;

  private Boolean cleanupEnabled;

  private Integer batchSize;

  private String description;

  private String createdBy;

  private String updatedBy;

  private Instant createdAt;

  private Instant updatedAt;
}
