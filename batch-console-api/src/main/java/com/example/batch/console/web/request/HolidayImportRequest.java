package com.example.batch.console.web.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

import java.util.List;

@Data
public class HolidayImportRequest {
    @NotNull private String tenantId;

    @NotEmpty @Valid private List<HolidayItem> items;

    @Data
    public static class HolidayItem {
        @NotNull private String bizDate;
        @NotNull private String dayType;
        private String holidayName;
        private String description;
    }
}
