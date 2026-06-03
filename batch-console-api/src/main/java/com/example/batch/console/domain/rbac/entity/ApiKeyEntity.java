package com.example.batch.console.domain.rbac.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import lombok.Data;

@Data
public class ApiKeyEntity {

  private Long id;

  private String tenantId;

  private String keyName;

  private String keyPrefix;

  /** 哈希值,不向 FE 序列化(防 DB 备份外泄后离线暴力)。 */
  @JsonIgnore private String keyHash;

  /** P1-1(V166):per-key 16B base64 salt;legacy sha256 行为 null。不向 FE 序列化。 */
  @JsonIgnore private String salt;

  /** P1-1(V166):{@code sha256}(legacy) | {@code pbkdf2}(默认)。不向 FE 序列化(KDF 细节内部用)。 */
  @JsonIgnore private String keyHashAlgo;

  private String scopes;

  private Boolean enabled;

  private Instant expiresAt;

  private Instant lastUsedAt;

  private String createdBy;

  private String revokedBy;

  private Instant revokedAt;

  private Instant createdAt;
}
