package com.example.batch.console.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

import java.util.Set;

@Data
public class ConfigSyncExportRequest {

    @NotBlank
    @Size(max = 64)
    private String sourceTenantId;

    @NotBlank
    @Size(max = 64)
    private String sourceEnv;

    @NotBlank
    @Size(max = 64)
    private String targetEnv;

    private Set<TenantConfigCopyRequest.ConfigType> configTypes;
}
