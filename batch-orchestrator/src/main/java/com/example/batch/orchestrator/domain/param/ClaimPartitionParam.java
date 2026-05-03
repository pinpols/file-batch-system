package com.example.batch.orchestrator.domain.param;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ClaimPartitionParam {
  private final String tenantId;
  private final Long id;
  private final String workerCode;
  private final Instant leaseExpireAt;
  private final String fromStatus;
  private final String toStatus;
  private final Long expectedVersion;

  /** ADR-014: nullable — lifecycle/internal claims may omit (columns cleared). */
  private final String currentInvocationId;

  private final Instant invocationStartedAt;
}
