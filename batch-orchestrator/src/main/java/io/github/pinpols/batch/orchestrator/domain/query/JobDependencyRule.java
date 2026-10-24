package io.github.pinpols.batch.orchestrator.domain.query;

import io.github.pinpols.batch.common.utils.EmptyChecks;

/** Immutable dependency-edge snapshot used to evaluate readiness and persist deferred launches. */
public record JobDependencyRule(String upstreamJobCode, String bizDateAlignment) {

  public static final String SAME_DAY = "SAME_DAY";
  public static final String PREVIOUS_BIZ_DAY = "PREVIOUS_BIZ_DAY";

  public JobDependencyRule {
    bizDateAlignment = EmptyChecks.isNull(bizDateAlignment) || EmptyChecks.isBlank(bizDateAlignment)
        ? SAME_DAY
        : bizDateAlignment.trim().toUpperCase(java.util.Locale.ROOT);
  }

  public static JobDependencyRule sameDay(String upstreamJobCode) {
    return new JobDependencyRule(upstreamJobCode, SAME_DAY);
  }
}
