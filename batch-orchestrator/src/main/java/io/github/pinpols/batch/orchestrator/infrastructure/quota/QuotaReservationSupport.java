package io.github.pinpols.batch.orchestrator.infrastructure.quota;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.scheduler.QuotaRuntimeStateService.QuotaReservationRequest;
import io.github.pinpols.batch.orchestrator.domain.scheduling.QuotaResetPolicy;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import java.util.Optional;
import java.util.function.Function;

/** Shared normalization for database and Redis quota reservation implementations. */
final class QuotaReservationSupport {

  private QuotaReservationSupport() {}

  static Precheck precheck(QuotaReservationRequest request) {
    if (request == null
        || request.owner() == null
        || !Texts.hasText(request.owner().tenantId())
        || !Texts.hasText(request.owner().quotaScope())
        || !Texts.hasText(request.owner().ownerCode())
        || request.policy() == null
        || request.policy().baseCap() <= 0) {
      return Precheck.notApplicable();
    }
    int normalizedBurst = Math.max(0, request.policy().burstLimit());
    int normalizedRequested = Math.max(1, request.requestedCount());
    QuotaResetPolicy policy = QuotaResetPolicy.from(request.policy().quotaResetPolicy());
    long cap = (long) request.policy().baseCap() + normalizedBurst;
    boolean staticCapExceeded = request.currentActiveCount() + normalizedRequested > cap;
    return new Precheck(true, normalizedBurst, normalizedRequested, policy, staticCapExceeded);
  }

  static Optional<ResourceCheck> resolveStaticReservation(
      QuotaReservationRequest request,
      Precheck precheck,
      Function<QuotaReservationRequest, ResourceCheck> waitForCapacity) {
    if (precheck.policy().isRuntimeManaged() && precheck.normalizedBurst() > 0) {
      return Optional.empty();
    }
    if (precheck.staticCapExceeded()) {
      return Optional.of(waitForCapacity.apply(request));
    }
    return Optional.of(ResourceCheck.allow());
  }

  record Precheck(
      boolean applicable,
      int normalizedBurst,
      int normalizedRequested,
      QuotaResetPolicy policy,
      boolean staticCapExceeded) {
    private static Precheck notApplicable() {
      return new Precheck(false, 0, 0, QuotaResetPolicy.NONE, false);
    }
  }
}
