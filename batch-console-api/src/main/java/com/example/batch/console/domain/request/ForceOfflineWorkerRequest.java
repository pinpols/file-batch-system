package com.example.batch.console.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ForceOfflineWorkerRequest {

    @NotBlank
    private String tenantId;
}
