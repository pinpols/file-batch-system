package com.example.batch.trigger.support;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class BatchDayCutoffCandidate {

    private Long id;
    private String tenantId;
    private String calendarCode;
    private LocalDate bizDate;
    private String dayStatus;
    private String timezone;
    private LocalTime cutoffTime;
}
