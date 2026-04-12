package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table(schema = "batch", value = "api_key")
public class ApiKeyEntity {

  @Id private Long id;

  @Column("tenant_id")
  private String tenantId;

  @Column("key_name")
  private String keyName;

  @Column("key_prefix")
  private String keyPrefix;

  @Column("key_hash")
  private String keyHash;

  @Column("scopes")
  private String scopes;

  @Column("enabled")
  private Boolean enabled;

  @Column("expires_at")
  private Instant expiresAt;

  @Column("last_used_at")
  private Instant lastUsedAt;

  @Column("created_by")
  private String createdBy;

  @Column("revoked_by")
  private String revokedBy;

  @Column("revoked_at")
  private Instant revokedAt;

  @Column("created_at")
  private Instant createdAt;
}
