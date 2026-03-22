package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("batch.quota_runtime_state")
public class QuotaRuntimeStateRecord {

    @Id
    private Long id;
    @Column("tenant_id")
    private String tenantId;
    @Column("quota_scope")
    private String quotaScope;
    @Column("owner_code")
    private String ownerCode;
    @Column("quota_reset_policy")
    private String quotaResetPolicy;
    @Column("window_started_at")
    private Instant windowStartedAt;
    @Column("window_expires_at")
    private Instant windowExpiresAt;
    @Column("peak_borrowed_count")
    private Integer peakBorrowedCount;
    @Column("last_reset_at")
    private Instant lastResetAt;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;
}
