package com.example.batch.console.web.request.file;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BusinessCalendarExcelApplyRequest {

  @Size(max = 512, message = "reason too long (max 512)")
  private String reason;
}
