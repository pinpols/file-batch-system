package com.example.batch.trigger.support;

import lombok.Data;

@Data
public class TriggerDescriptor {

    private String tenantId;
    private String jobCode;
    private String scheduleType;
    private String scheduleExpression;
    private String timezone;
    private String triggerMode;
    private String calendarCode;
    private String catchUpPolicy;
    private Integer catchUpMaxDays;
    private boolean enabled;
}
