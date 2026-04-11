package com.example.batch.trigger.support;

import lombok.Data;

import java.time.LocalDate;

@Data
public class CalendarHolidayRule {

    private LocalDate bizDate;
    private String dayType;
}
