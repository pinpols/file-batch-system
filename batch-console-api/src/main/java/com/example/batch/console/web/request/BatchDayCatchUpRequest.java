package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class BatchDayCatchUpRequest {

  @ValidTenantId private String tenantId;

  @NotBlank
  @Size(max = 128, message = "calendarCode too long (max 128)")
  private String calendarCode;

  private List<@Size(max = 128, message = "jobCode too long (max 128)") String> jobCodes;

  @Size(max = 512, message = "reason too long (max 512)")
  private String reason;
}
