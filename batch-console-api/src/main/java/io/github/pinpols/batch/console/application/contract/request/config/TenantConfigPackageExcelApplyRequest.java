package io.github.pinpols.batch.console.application.contract.request.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TenantConfigPackageExcelApplyRequest {

  @NotBlank
  private String previewToken;

  @Size(max = 512)
  private String reason;
}
