package com.example.batch.orchestrator.domain.param;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor(force = true)
@AllArgsConstructor
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

  /** Citus IMMUTABLE fix: 替代 COALESCE 内的 current_timestamp，由调用方传入认领时刻。 */
  private final Instant claimAt;
}
