package com.example.batch.trigger.support;

import java.time.LocalDate;
import lombok.Data;

@Data
public class CalendarHolidayRule {

    private LocalDate bizDate;
    private String dayType;
}
