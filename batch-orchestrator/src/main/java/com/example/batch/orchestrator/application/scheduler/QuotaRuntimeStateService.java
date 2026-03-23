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
        int currentPeak = state.getPeakBorrowedCount() == null ? 0 : Math.max(0, state.getPeakBorrowedCount());
        if (borrowedNeeded > currentPeak) {
            state.setPeakBorrowedCount(borrowedNeeded);
            state.setLastResetAt(state.getLastResetAt() == null ? now : state.getLastResetAt());
            state.setUpdatedAt(now);
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
        int peakBorrowed = state.getPeakBorrowedCount() == null ? 0 : Math.max(0, state.getPeakBorrowedCount());
        int remaining = Math.max(0, burstLimit - peakBorrowed);
        return new QuotaRuntimeSnapshot(
                policy.name(),
                burstLimit,
                peakBorrowed,
                remaining,
                state.getWindowStartedAt(),
                state.getWindowExpiresAt(),
                state.getLastResetAt()
        );
    }

    @Transactional
    public void reconcileExpiredStates(int slidingWindowHours) {
        Instant now = Instant.now();
        List<QuotaRuntimeStateRecord> expired = quotaRuntimeStateRepository.findExpired(now);
        for (QuotaRuntimeStateRecord state : expired) {
            QuotaResetPolicy policy = QuotaResetPolicy.from(state.getQuotaResetPolicy());
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
        QuotaRuntimeStateRecord created = new QuotaRuntimeStateRecord();
        created.setTenantId(tenantId);
        created.setQuotaScope(quotaScope);
        created.setOwnerCode(ownerCode);
        created.setQuotaResetPolicy(quotaResetPolicy);
        created.setPeakBorrowedCount(0);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        refreshState(created, QuotaResetPolicy.from(quotaResetPolicy), now, slidingWindowHours, false);
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
        if (!normalizedPolicy.equalsIgnoreCase(state.getQuotaResetPolicy())) {
            state.setQuotaResetPolicy(normalizedPolicy);
            changed = true;
        }
        if (!policy.isRuntimeManaged()) {
            if (state.getPeakBorrowedCount() == null || state.getPeakBorrowedCount() != 0) {
                state.setPeakBorrowedCount(0);
                changed = true;
            }
            if (state.getWindowStartedAt() != null || state.getWindowExpiresAt() != null) {
                state.setWindowStartedAt(null);
                state.setWindowExpiresAt(null);
                changed = true;
            }
            if (changed && persist) {
                state.setUpdatedAt(now);
                quotaRuntimeStateRepository.save(state);
            }
            return state;
        }

        if (policy == QuotaResetPolicy.CALENDAR_DAY) {
            ZonedDateTime nowZdt = now.atZone(QuotaResetPolicy.systemZone());
            Instant windowStart = QuotaResetPolicy.startOfCalendarDay(nowZdt).toInstant();
            Instant windowEnd = windowStart.plus(Duration.ofDays(1));
            if (!windowStart.equals(state.getWindowStartedAt()) || state.getWindowExpiresAt() == null || !now.isBefore(state.getWindowExpiresAt())) {
                state.setWindowStartedAt(windowStart);
                state.setWindowExpiresAt(windowEnd);
                state.setPeakBorrowedCount(0);
                state.setLastResetAt(now);
                changed = true;
            }
        } else if (policy == QuotaResetPolicy.SLIDING_WINDOW) {
            int normalizedHours = Math.max(1, slidingWindowHours);
            if (state.getWindowStartedAt() == null || state.getWindowExpiresAt() == null || !now.isBefore(state.getWindowExpiresAt())) {
                state.setWindowStartedAt(now);
                state.setWindowExpiresAt(now.plus(Duration.ofHours(normalizedHours)));
                state.setPeakBorrowedCount(0);
                state.setLastResetAt(now);
                changed = true;
            }
        }
        if (changed && persist) {
            state.setUpdatedAt(now);
            quotaRuntimeStateRepository.save(state);
        }
        return state;
    }

    private String normalizePolicy(String policy) {
        return QuotaResetPolicy.from(policy).name();
    }
}
