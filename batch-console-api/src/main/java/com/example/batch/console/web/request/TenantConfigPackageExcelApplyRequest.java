package com.example.batch.console.web.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TenantConfigPackageExcelApplyRequest {

  @Size(max = 512)
  private String reason;
}
