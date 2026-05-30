package com.example.batch.console.domain.job.web.request;

import com.example.batch.common.validation.ValidResourceCode;
import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CalendarSaveRequest {
  @ValidTenantId private String tenantId;

  @ValidResourceCode private String calendarCode;

  @NotBlank
  @Size(max = 256)
  private String calendarName;

  @NotBlank
  @Size(max = 64)
  private String timezone;

  private String holidayRollRule;
  private String catchUpPolicy;
  private Integer catchUpMaxDays;
  private Boolean enabled;
}
