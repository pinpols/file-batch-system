package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;
import org.springframework.data.relational.core.mapping.Column;

@Data
public class ArchivePolicyEntity {

  private Long id;

  @Column("tenant_id")
  private String tenantId;

  @Column("target_table")
  private String targetTable;

  @Column("retention_days")
  private Integer retentionDays;

  @Column("archive_enabled")
  private Boolean archiveEnabled;

  @Column("cleanup_enabled")
  private Boolean cleanupEnabled;

  @Column("batch_size")
  private Integer batchSize;

  @Column("description")
  private String description;

  @Column("created_by")
  private String createdBy;

  @Column("updated_by")
  private String updatedBy;

  @Column("created_at")
  private Instant createdAt;

  @Column("updated_at")
  private Instant updatedAt;
}
