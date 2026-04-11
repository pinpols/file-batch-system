package com.example.batch.orchestrator.application.scheduler;

import com.example.batch.orchestrator.domain.entity.QuotaRuntimeStateRecord;
import com.example.batch.orchestrator.domain.scheduler.QuotaResetPolicy;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import com.example.batch.orchestrator.repository.QuotaRuntimeStateRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class QuotaRuntimeStateService {

    public record QuotaRuntimeSnapshot(
            String quotaResetPolicy,
            Integer burstLimit,
            Integer peakBorrowedCount,
            Integer remainingBurst,
            Instant windowStartedAt,
            Instant windowExpiresAt,
            Instant lastResetAt) {}

    private final QuotaRuntimeStateRepository quotaRuntimeStateRepository;

    public record QuotaReservationOwner(String tenantId, String quotaScope, String ownerCode) {}

    public record QuotaReservationPolicy(
            String quotaResetPolicy, int baseCap, int burstLimit, int slidingWindowHours) {}

    public record QuotaReservationReason(String reasonCode, String reasonMessage) {}

    public record QuotaReservationRequest(
            QuotaReservationOwner owner,
            QuotaReservationPolicy policy,
            long currentActiveCount,
            int requestedCount,
            QuotaReservationReason reason) {}

    @Transactional
    public ResourceCheck evaluateAndReserve(QuotaReservationRequest request) {
        if (request == null
                || request.owner() == null
                || !StringUtils.hasText(request.owner().tenantId())
                || !StringUtils.hasText(request.owner().quotaScope())
                || !StringUtils.hasText(request.owner().ownerCode())) {
            return ResourceCheck.allow();
        }
        if (request.policy() == null || request.policy().baseCap() <= 0) {
            return ResourceCheck.allow();
        }
        int normalizedBurst = Math.max(0, request.policy().burstLimit());
        int normalizedRequested = Math.max(1, request.requestedCount());
        QuotaResetPolicy policy = QuotaResetPolicy.from(request.policy().quotaResetPolicy());
        if (!policy.isRuntimeManaged() || normalizedBurst == 0) {
            long cap = (long) request.policy().baseCap() + normalizedBurst;
            if (request.currentActiveCount() + normalizedRequested > cap) {
                return waitForCapacity(request);
            }
            return ResourceCheck.allow();
        }

        Instant now = Instant.now();
        QuotaRuntimeStateRecord state =
                loadOrCreate(
                        new StateContext(
                                request.owner(),
                                policy.name(),
                                now,
                                request.policy().slidingWindowHours()));
        state = refreshState(state, policy, now, request.policy().slidingWindowHours());

        int borrowedNeeded =
                Math.max(
                        0,
                        (int)
                                (request.currentActiveCount()
                                        + normalizedRequested
                                        - request.policy().baseCap()));
        if (borrowedNeeded == 0) {
            quotaRuntimeStateRepository.save(state);
            return ResourceCheck.allow();
        }
        if (borrowedNeeded > normalizedBurst) {
            return waitForCapacity(request);
        }
        int currentPeak =
                state.peakBorrowedCount() == null ? 0 : Math.max(0, state.peakBorrowedCount());
        if (borrowedNeeded > currentPeak) {
            state =
                    state.withPeakAndTimestamps(
                            borrowedNeeded,
                            state.lastResetAt() == null ? now : state.lastResetAt(),
                            now);
        }
        // M-4: 只要使用了突发容量就必须持久化状态，否则 lastUpdatedAt 漂移导致窗口过期误判
        quotaRuntimeStateRepository.save(state);
        return ResourceCheck.allow();
    }

    public record QuotaDescribeRequest(
            QuotaReservationOwner owner,
            String quotaResetPolicy,
            int burstLimit,
            int slidingWindowHours) {}

    @Transactional(readOnly = true)
    public QuotaRuntimeSnapshot describe(QuotaDescribeRequest request) {
        String tenantId = request.owner().tenantId();
        String quotaScope = request.owner().quotaScope();
        String ownerCode = request.owner().ownerCode();
        String quotaResetPolicy = request.quotaResetPolicy();
        int burstLimit = request.burstLimit();
        int slidingWindowHours = request.slidingWindowHours();
        if (!StringUtils.hasText(tenantId)
                || !StringUtils.hasText(quotaScope)
                || !StringUtils.hasText(ownerCode)) {
            return new QuotaRuntimeSnapshot(
                    normalizePolicy(quotaResetPolicy),
                    Math.max(0, burstLimit),
                    0,
                    Math.max(0, burstLimit),
                    null,
                    null,
                    null);
        }
        QuotaResetPolicy policy = QuotaResetPolicy.from(quotaResetPolicy);
        if (!policy.isRuntimeManaged() || burstLimit <= 0) {
            return new QuotaRuntimeSnapshot(
                    policy.name(),
                    Math.max(0, burstLimit),
                    0,
                    Math.max(0, burstLimit),
                    null,
                    null,
                    null);
        }
        QuotaRuntimeStateRecord state =
                quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(
                        tenantId, quotaScope, ownerCode);
        if (state == null) {
            return new QuotaRuntimeSnapshot(
                    policy.name(), burstLimit, 0, burstLimit, null, null, null);
        }
        state = refreshState(state, policy, Instant.now(), slidingWindowHours, false);
        if (state == null) {
            return new QuotaRuntimeSnapshot(
                    policy.name(), burstLimit, 0, burstLimit, null, null, null);
        }
        int peakBorrowed =
                state.peakBorrowedCount() == null ? 0 : Math.max(0, state.peakBorrowedCount());
        int remaining = Math.max(0, burstLimit - peakBorrowed);
        return new QuotaRuntimeSnapshot(
                policy.name(),
                burstLimit,
                peakBorrowed,
                remaining,
                state.windowStartedAt(),
                state.windowExpiresAt(),
                state.lastResetAt());
    }

    @Transactional
    public void reconcileExpiredStates(int slidingWindowHours) {
        Instant now = Instant.now();
        List<QuotaRuntimeStateRecord> expired = quotaRuntimeStateRepository.findExpired(now);
        for (QuotaRuntimeStateRecord state : expired) {
            QuotaResetPolicy policy = QuotaResetPolicy.from(state.quotaResetPolicy());
            refreshState(state, policy, now, slidingWindowHours, true);
        }
    }

    private record StateContext(
            QuotaReservationOwner owner,
            String quotaResetPolicy,
            Instant now,
            int slidingWindowHours) {}

    private QuotaRuntimeStateRecord loadOrCreate(StateContext ctx) {
        String tenantId = ctx.owner().tenantId();
        String quotaScope = ctx.owner().quotaScope();
        String ownerCode = ctx.owner().ownerCode();
        QuotaRuntimeStateRecord state =
                quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(
                        tenantId, quotaScope, ownerCode);
        if (state != null) {
            return state;
        }
        QuotaRuntimeStateRecord created =
                new QuotaRuntimeStateRecord(
                        null,
                        tenantId,
                        quotaScope,
                        ownerCode,
                        ctx.quotaResetPolicy(),
                        null,
                        null,
                        0,
                        null,
                        ctx.now(),
                        ctx.now());
        created =
                refreshState(
                        created,
                        QuotaResetPolicy.from(ctx.quotaResetPolicy()),
                        ctx.now(),
                        ctx.slidingWindowHours(),
                        false);
        return created;
    }

    private QuotaRuntimeStateRecord refreshState(
            QuotaRuntimeStateRecord state,
            QuotaResetPolicy policy,
            Instant now,
            int slidingWindowHours) {
        return refreshState(state, policy, now, slidingWindowHours, true);
    }

    private QuotaRuntimeStateRecord refreshState(
            QuotaRuntimeStateRecord state,
            QuotaResetPolicy policy,
            Instant now,
            int slidingWindowHours,
            boolean persist) {
        if (state == null) {
            return null;
        }
        String normalizedPolicy = policy == null ? QuotaResetPolicy.NONE.name() : policy.name();
        boolean changed = false;
        if (!normalizedPolicy.equalsIgnoreCase(state.quotaResetPolicy())) {
            state =
                    state.withRefresh(
                            normalizedPolicy,
                            state.windowStartedAt(),
                            state.windowExpiresAt(),
                            state.peakBorrowedCount() == null ? 0 : state.peakBorrowedCount(),
                            state.lastResetAt());
            changed = true;
        }
        if (!policy.isRuntimeManaged()) {
            if (state.peakBorrowedCount() == null
                    || state.peakBorrowedCount() != 0
                    || state.windowStartedAt() != null
                    || state.windowExpiresAt() != null) {
                state = state.withRefresh(normalizedPolicy, null, null, 0, state.lastResetAt());
                changed = true;
            }
            if (changed && persist) {
                quotaRuntimeStateRepository.save(state);
            }
            return state;
        }

        if (policy == QuotaResetPolicy.CALENDAR_DAY) {
            ZonedDateTime nowZdt = now.atZone(QuotaResetPolicy.systemZone());
            Instant windowStart = QuotaResetPolicy.startOfCalendarDay(nowZdt).toInstant();
            Instant windowEnd = windowStart.plus(Duration.ofDays(1));
            if (!windowStart.equals(state.windowStartedAt())
                    || state.windowExpiresAt() == null
                    || !now.isBefore(state.windowExpiresAt())) {
                state = state.withRefresh(normalizedPolicy, windowStart, windowEnd, 0, now);
                changed = true;
            }
        } else if (policy == QuotaResetPolicy.SLIDING_WINDOW) {
            int normalizedHours = Math.max(1, slidingWindowHours);
            if (state.windowStartedAt() == null
                    || state.windowExpiresAt() == null
                    || !now.isBefore(state.windowExpiresAt())) {
                state =
                        state.withRefresh(
                                normalizedPolicy,
                                now,
                                now.plus(Duration.ofHours(normalizedHours)),
                                0,
                                now);
                changed = true;
            }
        }
        if (changed && persist) {
            quotaRuntimeStateRepository.save(state);
        }
        return state;
    }

    private String normalizePolicy(String policy) {
        return QuotaResetPolicy.from(policy).name();
    }

    private ResourceCheck waitForCapacity(QuotaReservationRequest request) {
        QuotaReservationReason reason = request.reason();
        return ResourceCheck.waitForCapacity(
                reason == null ? null : reason.reasonCode(),
                reason == null ? null : reason.reasonMessage());
    }
}
