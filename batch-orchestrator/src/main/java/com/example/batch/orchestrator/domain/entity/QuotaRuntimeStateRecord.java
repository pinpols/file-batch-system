package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "batch", value = "quota_runtime_state")
public record QuotaRuntimeStateRecord(
        @Id Long id,
        @Column("tenant_id") String tenantId,
        @Column("quota_scope") String quotaScope,
        @Column("owner_code") String ownerCode,
        @Column("quota_reset_policy") String quotaResetPolicy,
        @Column("window_started_at") Instant windowStartedAt,
        @Column("window_expires_at") Instant windowExpiresAt,
        @Column("peak_borrowed_count") Integer peakBorrowedCount,
        @Column("last_reset_at") Instant lastResetAt,
        @Column("created_at") Instant createdAt,
        @Column("updated_at") Instant updatedAt
) {
    /** Update peak + last timestamps (called by evaluateAndReserve). */
    public QuotaRuntimeStateRecord withPeakAndTimestamps(int newPeak, Instant lastResetAt, Instant updatedAt) {
        return new QuotaRuntimeStateRecord(id, tenantId, quotaScope, ownerCode, quotaResetPolicy,
                windowStartedAt, windowExpiresAt, newPeak, lastResetAt, createdAt, updatedAt);
    }

    /** Full window refresh (called by reconcileExpiredStates / refreshState). */
    public QuotaRuntimeStateRecord withRefresh(String quotaResetPolicy,
                                               Instant windowStartedAt,
                                               Instant windowExpiresAt,
                                               int peakBorrowedCount,
                                               Instant lastResetAt) {
        return new QuotaRuntimeStateRecord(id, tenantId, quotaScope, ownerCode, quotaResetPolicy,
                windowStartedAt, windowExpiresAt, peakBorrowedCount, lastResetAt, createdAt, Instant.now());
    }
}
