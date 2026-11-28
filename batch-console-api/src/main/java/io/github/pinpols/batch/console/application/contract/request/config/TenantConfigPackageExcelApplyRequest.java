package io.github.pinpols.batch.console.application.contract.request.config;

import lombok.Data;

import jakarta.validation.constraints.Size;

@Data
public class TenantConfigPackageExcelApplyRequest {

    private String previewToken;

    @Size(max = 512)
    private String reason;
}
