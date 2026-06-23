package io.github.pinpols.batch.console.domain.rbac.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class SecretVersionEntity {

  private Long id;
  private String tenantId;
  private String secretRef;
  private String secretName;
  private Integer versionNo;
  private String secretStatus;
  private Boolean currentVersion;
  private Instant rotationWindowStartAt;
  private Instant rotationWindowEndAt;
  private Instant effectiveFromAt;
  private Instant effectiveToAt;
  private String secretPayload;
  private String rotationReason;
  private String createdBy;
  private String updatedBy;
  private Instant createdAt;
  private Instant updatedAt;
}
