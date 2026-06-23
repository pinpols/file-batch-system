package io.github.pinpols.batch.console.web.request.config;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TenantConfigPackageExcelApplyRequest {

  @Size(max = 512)
  private String reason;
}
