package com.example.batch.orchestrator.domain.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;

@Table(schema = "batch", value = "batch_day_instance")
public record BatchDayInstanceRecord(
        @Id Long id,
        @Column("tenant_id") String tenantId,
        @Column("calendar_code") String calendarCode,
        @Column("biz_date") LocalDate bizDate,
        @Column("day_status") String dayStatus,
        @Column("open_at") Instant openAt,
        @Column("cutoff_at") Instant cutoffAt,
        @Column("settled_at") Instant settledAt,
        @Column("sla_deadline_at") Instant slaDeadlineAt,
        @Column("late_count") Integer lateCount,
        @Column("catchup_count") Integer catchupCount,
        @Column("created_at") Instant createdAt,
        @Column("updated_at") Instant updatedAt) {

    public BatchDayInstanceRecord withSlaDeadlineAt(Instant slaDeadlineAt, Instant updatedAt) {
        return new BatchDayInstanceRecord(
                id,
                tenantId,
                calendarCode,
                bizDate,
                dayStatus,
                openAt,
                cutoffAt,
                settledAt,
                slaDeadlineAt,
                lateCount,
                catchupCount,
                createdAt,
                updatedAt);
    }

    public BatchDayInstanceRecord withLateCount(Integer lateCount, Instant updatedAt) {
        return new BatchDayInstanceRecord(
                id,
                tenantId,
                calendarCode,
                bizDate,
                dayStatus,
                openAt,
                cutoffAt,
                settledAt,
                slaDeadlineAt,
                lateCount,
                catchupCount,
                createdAt,
                updatedAt);
    }

    public BatchDayInstanceRecord withCatchupCount(Integer catchupCount, Instant updatedAt) {
        return new BatchDayInstanceRecord(
                id,
                tenantId,
                calendarCode,
                bizDate,
                dayStatus,
                openAt,
                cutoffAt,
                settledAt,
                slaDeadlineAt,
                lateCount,
                catchupCount,
                createdAt,
                updatedAt);
    }

    public BatchDayInstanceRecord withDayStatus(String dayStatus, Instant updatedAt) {
        return new BatchDayInstanceRecord(
                id,
                tenantId,
                calendarCode,
                bizDate,
                dayStatus,
                openAt,
                cutoffAt,
                settledAt,
                slaDeadlineAt,
                lateCount,
                catchupCount,
                createdAt,
                updatedAt);
    }

    public BatchDayInstanceRecord withCutoffAt(Instant cutoffAt, Instant updatedAt) {
        return new BatchDayInstanceRecord(
                id,
                tenantId,
                calendarCode,
                bizDate,
                dayStatus,
                openAt,
                cutoffAt,
                settledAt,
                slaDeadlineAt,
                lateCount,
                catchupCount,
                createdAt,
                updatedAt);
    }

    public BatchDayInstanceRecord withCutoff(Instant cutoffAt, Instant updatedAt) {
        return new BatchDayInstanceRecord(
                id,
                tenantId,
                calendarCode,
                bizDate,
                "CUTOFF",
                openAt,
                cutoffAt,
                settledAt,
                slaDeadlineAt,
                lateCount,
                catchupCount,
                createdAt,
                updatedAt);
    }

    public BatchDayInstanceRecord withSettled(
            String dayStatus, Instant settledAt, Instant updatedAt) {
        return new BatchDayInstanceRecord(
                id,
                tenantId,
                calendarCode,
                bizDate,
                dayStatus,
                openAt,
                cutoffAt,
                settledAt,
                slaDeadlineAt,
                lateCount,
                catchupCount,
                createdAt,
                updatedAt);
    }

    public BatchDayInstanceRecord withReopened(Instant updatedAt) {
        return new BatchDayInstanceRecord(
                id,
                tenantId,
                calendarCode,
                bizDate,
                "IN_FLIGHT",
                openAt,
                cutoffAt,
                null,
                slaDeadlineAt,
                lateCount,
                catchupCount,
                createdAt,
                updatedAt);
    }
}
