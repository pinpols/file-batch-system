package com.example.batch.orchestrator.domain.param;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RenewLeaseParam {
  private final String tenantId;
  private final Long id;
  private final String workerCode;
  private final Instant leaseExpireAt;

  /**
   * ADR-014: non-null → UPDATE matches {@code current_invocation_id}; null → transitional compat
   * (omit predicate).
   */
  private final String expectedInvocationId;
}
