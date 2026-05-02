package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class ApiKeyEntity {

  private Long id;

  private String tenantId;

  private String keyName;

  private String keyPrefix;

  private String keyHash;

  private String scopes;

  private Boolean enabled;

  private Instant expiresAt;

  private Instant lastUsedAt;

  private String createdBy;

  private String revokedBy;

  private Instant revokedAt;

  private Instant createdAt;
}
