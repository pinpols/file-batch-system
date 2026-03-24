package com.example.batch.orchestrator.application.scheduler;

import com.example.batch.orchestrator.domain.entity.QuotaRuntimeStateRecord;
import com.example.batch.orchestrator.domain.scheduler.QuotaResetPolicy;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import com.example.batch.orchestrator.repository.QuotaRuntimeStateRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
            Instant lastResetAt
    ) {
    }

    private final QuotaRuntimeStateRepository quotaRuntimeStateRepository;

    @Transactional
    public ResourceCheck evaluateAndReserve(String tenantId,
                                            String quotaScope,
                                            String ownerCode,
                                            String quotaResetPolicy,
                                            int baseCap,
                                            int burstLimit,
                                            long currentActiveCount,
                                            int requestedCount,
                                            int slidingWindowHours,
                                            String reasonCode,
                                            String reasonMessage) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(quotaScope) || !StringUtils.hasText(ownerCode)) {
            return ResourceCheck.allow();
        }
        if (baseCap <= 0) {
            return ResourceCheck.allow();
        }
        int normalizedBurst = Math.max(0, burstLimit);
        int normalizedRequested = Math.max(1, requestedCount);
        QuotaResetPolicy policy = QuotaResetPolicy.from(quotaResetPolicy);
        if (!policy.isRuntimeManaged() || normalizedBurst == 0) {
            long cap = (long) baseCap + normalizedBurst;
            if (currentActiveCount + normalizedRequested > cap) {
                return ResourceCheck.waitForCapacity(reasonCode, reasonMessage);
            }
            return ResourceCheck.allow();
        }

        Instant now = Instant.now();
        QuotaRuntimeStateRecord state = loadOrCreate(tenantId, quotaScope, ownerCode, policy.name(), now, slidingWindowHours);
        state = refreshState(state, policy, now, slidingWindowHours);

        int borrowedNeeded = Math.max(0, (int) (currentActiveCount + normalizedRequested - baseCap));
        if (borrowedNeeded == 0) {
            quotaRuntimeStateRepository.save(state);
            return ResourceCheck.allow();
        }
        if (borrowedNeeded > normalizedBurst) {
            return ResourceCheck.waitForCapacity(reasonCode, reasonMessage);
        }
        int currentPeak = state.peakBorrowedCount() == null ? 0 : Math.max(0, state.peakBorrowedCount());
        if (borrowedNeeded > currentPeak) {
            state = state.withPeakAndTimestamps(
                    borrowedNeeded,
                    state.lastResetAt() == null ? now : state.lastResetAt(),
                    now);
            quotaRuntimeStateRepository.save(state);
        }
        return ResourceCheck.allow();
    }

    @Transactional(readOnly = true)
    public QuotaRuntimeSnapshot describe(String tenantId,
                                         String quotaScope,
                                         String ownerCode,
                                         String quotaResetPolicy,
                                         int burstLimit,
                                         int slidingWindowHours) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(quotaScope) || !StringUtils.hasText(ownerCode)) {
            return new QuotaRuntimeSnapshot(normalizePolicy(quotaResetPolicy), Math.max(0, burstLimit), 0, Math.max(0, burstLimit), null, null, null);
        }
        QuotaResetPolicy policy = QuotaResetPolicy.from(quotaResetPolicy);
        if (!policy.isRuntimeManaged() || burstLimit <= 0) {
            return new QuotaRuntimeSnapshot(policy.name(), Math.max(0, burstLimit), 0, Math.max(0, burstLimit), null, null, null);
        }
        QuotaRuntimeStateRecord state = quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(tenantId, quotaScope, ownerCode);
        if (state == null) {
            return new QuotaRuntimeSnapshot(policy.name(), burstLimit, 0, burstLimit, null, null, null);
        }
        state = refreshState(state, policy, Instant.now(), slidingWindowHours, false);
        if (state == null) {
            return new QuotaRuntimeSnapshot(policy.name(), burstLimit, 0, burstLimit, null, null, null);
        }
        int peakBorrowed = state.peakBorrowedCount() == null ? 0 : Math.max(0, state.peakBorrowedCount());
        int remaining = Math.max(0, burstLimit - peakBorrowed);
        return new QuotaRuntimeSnapshot(
                policy.name(),
                burstLimit,
                peakBorrowed,
                remaining,
                state.windowStartedAt(),
                state.windowExpiresAt(),
                state.lastResetAt()
        );
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

    private QuotaRuntimeStateRecord loadOrCreate(String tenantId,
                                                 String quotaScope,
                                                 String ownerCode,
                                                 String quotaResetPolicy,
                                                 Instant now,
                                                 int slidingWindowHours) {
        QuotaRuntimeStateRecord state = quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(tenantId, quotaScope, ownerCode);
        if (state != null) {
            return state;
        }
        QuotaRuntimeStateRecord created = new QuotaRuntimeStateRecord(
                null, tenantId, quotaScope, ownerCode, quotaResetPolicy,
                null, null, 0, null, now, now);
        created = refreshState(created, QuotaResetPolicy.from(quotaResetPolicy), now, slidingWindowHours, false);
        return created;
    }

    private QuotaRuntimeStateRecord refreshState(QuotaRuntimeStateRecord state,
                                                 QuotaResetPolicy policy,
                                                 Instant now,
                                                 int slidingWindowHours) {
        return refreshState(state, policy, now, slidingWindowHours, true);
    }

    private QuotaRuntimeStateRecord refreshState(QuotaRuntimeStateRecord state,
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
            state = state.withRefresh(normalizedPolicy, state.windowStartedAt(), state.windowExpiresAt(),
                    state.peakBorrowedCount() == null ? 0 : state.peakBorrowedCount(), state.lastResetAt());
            changed = true;
        }
        if (!policy.isRuntimeManaged()) {
            if (state.peakBorrowedCount() == null || state.peakBorrowedCount() != 0
                    || state.windowStartedAt() != null || state.windowExpiresAt() != null) {
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
            if (!windowStart.equals(state.windowStartedAt()) || state.windowExpiresAt() == null || !now.isBefore(state.windowExpiresAt())) {
                state = state.withRefresh(normalizedPolicy, windowStart, windowEnd, 0, now);
                changed = true;
            }
        } else if (policy == QuotaResetPolicy.SLIDING_WINDOW) {
            int normalizedHours = Math.max(1, slidingWindowHours);
            if (state.windowStartedAt() == null || state.windowExpiresAt() == null || !now.isBefore(state.windowExpiresAt())) {
                state = state.withRefresh(normalizedPolicy, now, now.plus(Duration.ofHours(normalizedHours)), 0, now);
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
}
