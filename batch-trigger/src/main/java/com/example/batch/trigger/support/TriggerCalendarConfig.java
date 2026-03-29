package com.example.batch.trigger.support;

import java.time.LocalTime;
import lombok.Data;

@Data
public class TriggerCalendarConfig {

    private Long id;
    private String tenantId;
    private String calendarCode;
    private String timezone;
    private String holidayRollRule;
    private LocalTime cutoffTime;
}
