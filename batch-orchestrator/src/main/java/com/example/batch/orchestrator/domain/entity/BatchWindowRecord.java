package com.example.batch.orchestrator.domain.entity;

import java.time.LocalTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "batch", value = "batch_window")
public record BatchWindowRecord(
    @Id Long id,
    @Column("tenant_id") String tenantId,
    @Column("window_code") String windowCode,
    @Column("window_name") String windowName,
    @Column("timezone") String timezone,
    @Column("start_time") LocalTime startTime,
    @Column("end_time") LocalTime endTime,
    @Column("end_strategy") String endStrategy,
    @Column("out_of_window_action") String outOfWindowAction,
    @Column("allow_cross_day") Boolean allowCrossDay,
    @Column("enabled") Boolean enabled) {}
