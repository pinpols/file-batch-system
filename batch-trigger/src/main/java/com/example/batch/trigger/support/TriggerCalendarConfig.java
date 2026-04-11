package com.example.batch.trigger.support;

import lombok.Data;

import java.time.LocalTime;

@Data
public class TriggerCalendarConfig {

    private Long id;
    private String tenantId;
    private String calendarCode;
    private String timezone;
    private String holidayRollRule;
    private LocalTime cutoffTime;
}
