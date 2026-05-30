package com.example.batch.console.domain.job.param;

import lombok.Data;

@Data
public class BusinessCalendarUpsertParam {

  private String tenantId;
  private String calendarCode;
  private String calendarName;
  private String timezone;
  private String holidayRollRule;
  private String catchUpPolicy;
  private Integer catchUpMaxDays;
  private Boolean enabled;
  private String description;
  private String createdBy;
  private String updatedBy;
}
