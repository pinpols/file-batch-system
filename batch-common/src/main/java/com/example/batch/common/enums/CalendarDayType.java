package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum CalendarDayType {
    HOLIDAY("HOLIDAY", "节假日"),
    WORKDAY_OVERRIDE("WORKDAY_OVERRIDE", "补班日");

    private final String code;
    private final String label;

    CalendarDayType(String code, String label) { this.code = code; this.label = label; }

    public String code() { return code; }
    public String label() { return label; }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(CalendarDayType::code).collect(Collectors.toUnmodifiableSet());
    }
}
