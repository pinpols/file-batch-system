package com.example.batch.console.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class UpdateTenantRequest {

    @NotBlank
    @Size(max = 256)
    private String tenantName;

    @Size(max = 512)
    private String description;
}
