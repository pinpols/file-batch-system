package com.example.batch.console.web.request.file;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class HolidaySaveRequest {
  @NotNull private String tenantId;
  @NotNull private String bizDate;
  @NotNull private String dayType;
  private String holidayName;
  private String description;
}
