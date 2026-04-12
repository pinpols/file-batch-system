package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table(schema = "batch", value = "resource_tag")
public class ResourceTagEntity {

  @Id private Long id;

  @Column("tenant_id")
  private String tenantId;

  @Column("resource_type")
  private String resourceType;

  @Column("resource_code")
  private String resourceCode;

  @Column("tag_key")
  private String tagKey;

  @Column("tag_value")
  private String tagValue;

  @Column("created_by")
  private String createdBy;

  @Column("created_at")
  private Instant createdAt;
}
