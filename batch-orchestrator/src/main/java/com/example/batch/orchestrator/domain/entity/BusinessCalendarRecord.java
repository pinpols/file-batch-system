package com.example.batch.orchestrator.domain.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("batch.business_calendar")
public class BusinessCalendarRecord {

    @Id
    private Long id;
    @Column("tenant_id")
    private String tenantId;
    @Column("calendar_code")
    private String calendarCode;
    @Column("calendar_name")
    private String calendarName;
    @Column("timezone")
    private String timezone;
    @Column("holiday_roll_rule")
    private String holidayRollRule;
    @Column("catch_up_policy")
    private String catchUpPolicy;
    @Column("catch_up_max_days")
    private Integer catchUpMaxDays;
    @Column("enabled")
    private Boolean enabled;
}
