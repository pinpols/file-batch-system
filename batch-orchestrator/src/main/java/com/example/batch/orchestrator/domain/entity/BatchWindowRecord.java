package com.example.batch.orchestrator.domain.entity;

import java.time.LocalTime;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("batch.batch_window")
public class BatchWindowRecord {

    @Id
    private Long id;
    @Column("tenant_id")
    private String tenantId;
    @Column("window_code")
    private String windowCode;
    @Column("window_name")
    private String windowName;
    @Column("timezone")
    private String timezone;
    @Column("start_time")
    private LocalTime startTime;
    @Column("end_time")
    private LocalTime endTime;
    @Column("end_strategy")
    private String endStrategy;
    @Column("out_of_window_action")
    private String outOfWindowAction;
    @Column("allow_cross_day")
    private Boolean allowCrossDay;
    @Column("enabled")
    private Boolean enabled;
}
