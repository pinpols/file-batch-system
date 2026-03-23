package com.example.batch.console.web.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ForceOfflineWorkerRequest {

    @NotBlank
    private String tenantId;
}
